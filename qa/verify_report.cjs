/**
 * 问数报告渲染验收（Ticket 18）。
 *
 * 为什么要单独一个脚本：Ticket 11 的判据是「reportHtml 含 echarts」「series 长度 = rows 长度」——
 * 这两条都是**字符串层面**的，图有没有真的画出来、用户看到的是一个数还是一张图，从来没验过。
 * 结果就是线上报告一直是一块空白图 + 一张表，而所有脚本全绿（因为字符串里确实有 echarts）。
 *
 * 本脚本补的正是这一层：用真实浏览器打开报告 iframe，检查
 *   1. 本地 echarts 是否真的加载成功（window.echarts 存在）
 *   2. bar 场景是否真的产出了 canvas 且有像素（不是白板）
 *   3. metric 场景是否渲染成卡片、值就是那一个数，且没有空图容器
 *   4. 报告是否真的带口径 / 条件 / 明细 / SQL
 *   5. 全程没有对外网（CDN）的请求
 *
 * 做法同 smoke_frontend.cjs：Node 托管 frontend/dist + 同源反代 /api 与 /report-assets，
 * puppeteer-core 驱动本机 Chrome。
 *
 * 用法：NODE_PATH=frontend/node_modules node qa/verify_report.cjs
 */
const http = require('http')
const fs = require('fs')
const path = require('path')

const DIST = path.join(__dirname, '..', 'frontend', 'dist')
const API_PORT = Number(process.env.DP_PORT || 18080)
const PORT = Number(process.env.REPORT_PORT || 18096)
const SHOT_DIR = path.join(__dirname, '..', 'docs', 'screenshots')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.png': 'image/png',
}

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

function startServer() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      // /api 与 /report-assets 都反代到后端：报告 iframe 的相对路径按父页面 origin 解析，
      // 所以图表库请求会落在这里，不代理就是 404 → 图空白（Ticket 18 的坑）
      if (req.url.startsWith('/api/') || req.url.startsWith('/report-assets/')) {
        const proxy = http.request(
          { host: '127.0.0.1', port: API_PORT, path: req.url, method: req.method, headers: req.headers },
          (r) => {
            res.writeHead(r.statusCode, r.headers)
            r.pipe(res)
          },
        )
        proxy.on('error', () => {
          res.writeHead(502, { 'Content-Type': 'text/plain' })
          res.end('backend down')
        })
        req.pipe(proxy)
        return
      }
      const clean = decodeURIComponent(req.url.split('?')[0])
      let file = path.join(DIST, clean)
      if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(DIST, 'index.html')
      res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' })
      fs.createReadStream(file).pipe(res)
    })
    server.listen(PORT, '127.0.0.1', () => resolve(server))
  })
}

/** 报告是 srcdoc iframe：frame url 是 about:srcdoc，等它里面有内容 */
async function reportFrame(page, timeoutMs = 40000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const f = page.frames().find((fr) => fr.url() === 'about:srcdoc')
    if (f) {
      try {
        const ready = await f.evaluate(() => !!document.body && document.body.innerText.trim().length > 20)
        if (ready) return f
      } catch {
        /* frame 还在导航 */
      }
    }
    await sleep(400)
  }
  throw new Error('报告 iframe 没出现或一直是空的')
}

/** 按 placeholder 填问题（React 受控组件必须用原生 setter + input 事件） */
async function ask(page, question) {
  await page.evaluate((q) => {
    const el = [...document.querySelectorAll('textarea, input')].find(
      (i) => (i.placeholder || '').startsWith('用中文问'),
    )
    if (!el) throw new Error('找不到问题输入框')
    const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, q)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }, question)
  const clicked = await page.evaluate(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.innerText.replace(/\s+/g, '') === '直接问')
    if (!btn) return false
    btn.click()
    return true
  })
  if (!clicked) throw new Error('点不到「直接问」')
}

;(async () => {
  if (!fs.existsSync(path.join(DIST, 'index.html'))) {
    console.error('缺 frontend/dist，先 cd frontend && npm run build')
    process.exit(2)
  }
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

  const server = await startServer()
  const BASE = `http://127.0.0.1:${PORT}`
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })

  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1600, height: 1100 })

    const consoleErrors = []
    const external = []
    page.on('console', (m) => {
      if (m.type() === 'error') consoleErrors.push(m.text())
    })
    page.on('request', (r) => {
      const u = r.url()
      if (/^https?:\/\//.test(u) && !u.startsWith(BASE)) external.push(u)
    })

    console.log('== 0. 图表库同源可用 ==')
    const lib = await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded', timeout: 30000 })
      .then(() => page.evaluate(async () => {
        const r = await fetch('/report-assets/echarts.min.js')
        return { status: r.status, text: (await r.text()).slice(0, 60) }
      }))
    check('/report-assets/echarts.min.js 由同源提供（200）', lib.status === 200, lib.status)
    check('返回的是 echarts 而不是 SPA 兜底的 index.html', /Licensed to the Apache|echarts/i.test(lib.text), lib.text)

    console.log('== 1. 单指标（metric）：卡片 + 口径，不是一块空图 ==')
    await ask(page, '10月19日小程序渠道的GMV是多少')
    const f1 = await reportFrame(page)
    const m = await f1.evaluate(() => ({
      cards: [...document.querySelectorAll('.card')].map((c) => ({
        v: (c.querySelector('.v') || {}).innerText,
        k: (c.querySelector('.k') || {}).innerText,
      })),
      hasChartDiv: !!document.getElementById('chart'),
      canvases: document.querySelectorAll('canvas').length,
      text: document.body.innerText,
      echartsLoaded: typeof window.echarts !== 'undefined',
    }))
    check('metric：渲染大数字卡片', m.cards.length === 1, m.cards)
    check('metric：卡片值 = 15604.97', (m.cards[0] || {}).v === '15604.97', m.cards[0])
    check('metric：卡片标签是指标 id（gmv_channel）', (m.cards[0] || {}).k === 'gmv_channel', m.cards[0])
    check('metric：没有空图容器 #chart', m.hasChartDiv === false, m.hasChartDiv)
    check('报告写明指标口径（SUM(gmv)）', m.text.includes('SUM(gmv)'), '')
    check('报告写明数据源表', m.text.includes('ads_channel_conversion_di'), '')
    check('报告写明过滤条件（dt / channel_name）', m.text.includes('dt = 2026-10-19') && m.text.includes('channel_name = 小程序'), '')
    check('报告带五道闸门结论', m.text.includes('存在性=PASS') && m.text.includes('结果自检=PASS'), '')
    check('报告带结果明细与 SQL', m.text.includes('结果明细') && m.text.includes('生成的 SQL'), '')
    await page.screenshot({ path: path.join(SHOT_DIR, 'report_metric.png') })

    console.log('== 2. 多行（bar）：echarts 真的画出图，不是白板 ==')
    // 用评测集里明确为 bar 的题：「各渠道的GMV分别是多少」在 stub 下识别不了（会 REJECTED），
    // 报告就没有 chartType 也没有 html —— 那是另一个问题，不是本脚本要验的
    await ask(page, '10月19日各渠道的支付转化率分别是多少？按转化率从高到低排序')
    // 等新报告：旧 iframe 会被替换，这里等出现 canvas 的 frame
    let f2 = null
    for (let i = 0; i < 100; i++) {
      for (const fr of page.frames()) {
        if (fr.url() !== 'about:srcdoc') continue
        try {
          const c = await fr.evaluate(() => document.querySelectorAll('canvas').length)
          if (c > 0) {
            f2 = fr
            break
          }
        } catch {
          /* ignore */
        }
      }
      if (f2) break
      await sleep(400)
    }
    if (!f2) {
      check('bar：报告里出现 echarts canvas', false, '40s 内没等到 canvas')
    } else {
      const b = await f2.evaluate(() => {
        const cv = document.querySelector('canvas')
        const rect = cv.getBoundingClientRect()
        return {
          hasChartDiv: !!document.getElementById('chart'),
          echartsLoaded: typeof window.echarts !== 'undefined',
          w: cv.width,
          h: cv.height,
          cssW: Math.round(rect.width),
          cssH: Math.round(rect.height),
          // 纯白画布的 PNG 极小；有坐标轴/柱子的画布明显更大
          dataUrlLen: cv.toDataURL().length,
          text: document.body.innerText,
        }
      })
      check('bar：echarts 在 iframe 内加载成功', b.echartsLoaded === true, b.echartsLoaded)
      check('bar：canvas 尺寸非零', b.w > 0 && b.h > 0 && b.cssH > 100, `${b.w}x${b.h} css=${b.cssW}x${b.cssH}`)
      check('bar：画布不是空白（有实际像素）', b.dataUrlLen > 3000, `dataURL ${b.dataUrlLen} 字符`)
      check('bar：3 行数据 → 3 条 series 名（APP/小程序/网页）',
        b.text.includes('APP') && b.text.includes('小程序') && b.text.includes('网页'), '')
      await page.screenshot({ path: path.join(SHOT_DIR, 'report_bar.png') })
    }

    console.log('== 3. 无外网依赖 / 无脚本报错 ==')
    check('全程没有对外网的请求（CDN 已本地化）', external.length === 0, external.slice(0, 5))
    check('无 console error', consoleErrors.length === 0, consoleErrors.slice(0, 3))
  } finally {
    await browser.close()
    server.close()
  }

  console.log(`\n结果：${pass} 通过 / ${failed} 失败`)
  process.exit(failed === 0 ? 0 : 1)
})().catch((e) => {
  console.error('脚本异常：', e.message)
  process.exit(2)
})
