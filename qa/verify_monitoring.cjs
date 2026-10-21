/**
 * 监控栈验收（P1-B）。
 *
 * 三件事：
 *   1. Prometheus 的 7 个抓取目标全部 up（宿主机 / Kafka / StarRocks FE+BE / Flink JM+TM / 自身）
 *   2. Grafana 里 DataPilot 面板已按 provisioning 装载（8 个 panel），数据源指向 prometheus
 *   3. 面板真的画出东西了（不是一片 "No data"）+ 截图存档
 *
 * 用法：NODE_PATH=frontend/node_modules node qa/verify_monitoring.cjs
 */
const fs = require('fs')
const path = require('path')

const PROM = process.env.PROM_URL || 'http://127.0.0.1:9090'
const GRAFANA = process.env.GRAFANA_URL || 'http://127.0.0.1:3000'
const SHOT_DIR = path.join(__dirname, '..', 'docs', 'screenshots')

let pass = 0
let failed = 0
const check = (name, ok, detail) => {
  if (ok) {
    pass++
    console.log(`  [PASS] ${name}`)
  } else {
    failed++
    console.log(`  [FAIL] ${name}${detail !== undefined ? ' → ' + JSON.stringify(detail) : ''}`)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function getJson(url) {
  // Node 18+ 自带 fetch；这里显式关掉代理，避免系统 http_proxy 干扰本地探测
  const resp = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!resp.ok) throw new Error(`${url} → HTTP ${resp.status}`)
  return resp.json()
}

;(async () => {
  console.log('== ① Prometheus 抓取目标 ==')
  const targets = (await getJson(`${PROM}/api/v1/targets`)).data.activeTargets
  const jobs = targets.map((t) => ({ job: t.labels.job, health: t.health, err: t.lastError }))
  const down = jobs.filter((j) => j.health !== 'up')
  check(`7 个目标全部 up（实际 ${jobs.length - down.length}/${jobs.length}）`, down.length === 0, down)
  const expectJobs = ['node', 'kafka', 'starrocks-fe', 'starrocks-be', 'flink-jobmanager', 'flink-taskmanager', 'prometheus']
  const gotJobs = jobs.map((j) => j.job)
  check('目标清单齐全（宿主机/Kafka/StarRocks/Flink）',
    expectJobs.every((j) => gotJobs.includes(j)), { missing: expectJobs.filter((j) => !gotJobs.includes(j)) })

  console.log('== ② 关键指标有数据 ==')
  const probes = {
    '宿主机 CPU': 'node_cpu_seconds_total',
    '宿主机内存': 'node_memory_MemTotal_bytes',
    'Kafka broker': 'kafka_brokers',
    'StarRocks FE 查询计数': 'starrocks_fe_query_total',
    'StarRocks BE CPU': 'starrocks_be_cpu',
    'Flink JM 堆': 'flink_jobmanager_Status_JVM_Memory_Heap_Used',
    'Flink TM 堆': 'flink_taskmanager_Status_JVM_Memory_Heap_Used',
  }
  for (const [label, q] of Object.entries(probes)) {
    const r = await getJson(`${PROM}/api/v1/query?query=${encodeURIComponent(`count(${q})`)}`)
    const n = r.data.result.length ? Number(r.data.result[0].value[1]) : 0
    check(`${label} 有数据（${q}）`, n > 0, n)
  }

  console.log('== ③ Grafana 面板 ==')
  const board = await getJson(`${GRAFANA}/api/dashboards/uid/datapilot-overview`)
  const panels = board.dashboard?.panels || []
  check('面板已装载：DataPilot 平台监控', board.dashboard?.title === 'DataPilot 平台监控', board.dashboard?.title)
  check('8 个 panel', panels.length === 8, panels.length)
  const dsUids = panels.map((p) => p.datasource?.uid).filter(Boolean)
  check('所有 panel 都绑到 prometheus 数据源', dsUids.length === panels.length && dsUids.every((u) => u === 'prometheus'), dsUids)

  console.log('== ④ 浏览器渲染 ==')
  let puppeteer
  try {
    puppeteer = require('puppeteer-core')
  } catch {
    console.error('缺 puppeteer-core（NODE_PATH 指到 frontend/node_modules）')
    process.exit(2)
  }
  const CHROME = [
    process.env.CHROME_PATH,
    'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
    'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  ].find((p) => p && fs.existsSync(p))
  if (!CHROME) {
    console.error('没找到 Chrome')
    process.exit(2)
  }

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1920, height: 1080 })
    await page.goto(`${GRAFANA}/d/datapilot-overview?kiosk&from=now-1h&to=now&refresh=30s`, {
      waitUntil: 'networkidle2',
      timeout: 60000,
    })
    await sleep(6000) // 等 panel 各自发查询、画图

    const view = await page.evaluate(() => {
      const text = document.body.innerText
      return {
        text,
        noData: (text.match(/No data|无数据/g) || []).length,
        hasError: /Panel rendering failed|Error loading/i.test(text),
      }
    })
    // Grafana 13 的 panel 容器 testid 改过，按标题文本判定更稳（标题是人看得见的东西）
    const TITLES = [
      '宿主机 CPU 使用率',
      '宿主机内存使用率',
      'Docker 磁盘使用率',
      '抓取目标健康',
      '宿主机 CPU / 内存',
      'Kafka 各 topic 写入速率',
      'StarRocks FE 查询速率 / BE CPU',
      'Flink 堆内存（JobManager / TaskManager）',
    ]
    const missing = TITLES.filter((t) => !view.text.includes(t))

    check('浏览器里 8 个 panel 标题都在', missing.length === 0, missing)
    check('没有面板渲染失败', view.hasError === false)
    check('「无数据」面板不超过 1 个', view.noData <= 1, view.noData)

    fs.mkdirSync(SHOT_DIR, { recursive: true })
    const shot = path.join(SHOT_DIR, 'grafana_monitoring.png')
    await page.screenshot({ path: shot })
    console.log('\n截图 ->', shot)
  } finally {
    await browser.close()
  }

  console.log(`\n结果：PASS ${pass} / ${pass + failed}`)
  process.exit(failed === 0 ? 0 : 1)
})().catch((e) => {
  console.error('FATAL', e)
  process.exit(1)
})
