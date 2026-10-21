/**
 * Ticket 13 前端冒烟：真实浏览器 + 真实后端，只断言外部可见行为。
 *
 * 为什么不用「fetch 一遍接口」冒充前端测试：那样测的是 API，不是页面。
 * 页面会挂的原因恰恰在接口之外 —— React 崩了、字段名拼错、antd 版本不兼容、
 * 组件没重新渲染。这些只有把页面真跑起来才看得见。
 *
 * 做法：
 *   1. 用内置 http 服务把 frontend/dist 静态托管在 5175，并把 /api 反代到后端
 *      （等于本地复刻一遍 Nginx 同源反代，顺带验证「页面代码只用相对路径」这件事）
 *   2. puppeteer-core 驱动本机 Chrome（无头），点按钮、读 DOM
 *   3. 断言点：页面骨架 / 探活 / 问数链路 / 五道闸门 / 参数化 SQL / 报告 iframe /
 *      HITL 预览→改值→执行 / 指标页负向校验 / 元数据页白名单增删 / 闸门配置页
 *
 * 用法（依赖前端目录的 node_modules，故用 NODE_PATH 指过去）：
 *   NODE_PATH=frontend/node_modules node qa/smoke_frontend.cjs [后端地址]
 * 例：
 *   NODE_PATH=frontend/node_modules node qa/smoke_frontend.cjs http://127.0.0.1:18080
 *
 * 前置：后端已启动；frontend 已 npm run build。
 * 退出码：0 通过 / 1 断言失败 / 2 环境不满足（缺 dist、缺浏览器）
 */
const http = require('node:http')
const fs = require('node:fs')
const path = require('node:path')

const HERE = __dirname
const ROOT = path.resolve(HERE, '..')
const DIST = path.join(ROOT, 'frontend', 'dist')
const BACKEND = (process.argv[2] || 'http://127.0.0.1:18080').replace(/\/$/, '')
const PORT = Number(process.env.SMOKE_PORT || 5175)
const BASE = `http://127.0.0.1:${PORT}`

const CHROME_CANDIDATES = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
].filter(Boolean)

const checks = []
let failed = 0

function check(name, ok, detail) {
  checks.push({ name, ok: !!ok, detail })
  if (!ok) failed++
  const mark = ok ? 'PASS' : 'FAIL'
  console.log(`[${mark}] ${name}${detail ? ' :: ' + detail : ''}`)
}

function fail(msg) {
  console.error(msg)
  process.exit(2)
}

// ---------------------------------------------------------------- 静态托管 + 反代
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
}

function proxy(req, res) {
  const url = new URL(BACKEND)
  const upstream = http.request(
    {
      hostname: url.hostname,
      port: url.port || 80,
      path: req.url,
      method: req.method,
      headers: { ...req.headers, host: url.host },
    },
    (up) => {
      res.writeHead(up.statusCode || 502, up.headers)
      up.pipe(res)
    },
  )
  upstream.on('error', (e) => {
    res.writeHead(502, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ error: '后端不可达：' + e.message }))
  })
  req.pipe(upstream)
}

function serveStatic(req, res) {
  const rel = decodeURIComponent(req.url.split('?')[0])
  let file = path.join(DIST, rel === '/' ? 'index.html' : rel)
  if (!file.startsWith(DIST)) {
    res.writeHead(403).end()
    return
  }
  // SPA 回退：前端路由（/metrics 等）刷新时要落到 index.html，否则 404
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    file = path.join(DIST, 'index.html')
  }
  if (!fs.existsSync(file)) {
    res.writeHead(404).end('dist 里没有这个文件')
    return
  }
  res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' })
  fs.createReadStream(file).pipe(res)
}

// ---------------------------------------------------------------- 浏览器辅助
/**
 * 按可见文字找可点击元素。
 *
 * 两个坑必须在这里吞掉，否则每条断言都会「莫名其妙」地失败：
 *  1. antd 会给两个汉字的按钮自动插空格（「保存」渲染成「保 存」），所以比较前两边都去空白；
 *  2. 按钮 loading 时 antd 会置 disabled，这时点击是无效的 —— 必须等到它可点再点。
 *     这正是第一版脚本里「先预览」点了没反应、抽屉等到超时的真因。
 */
function normalize(s) {
  return (s || '').replace(/\s+/g, '')
}

async function clickButton(page, text, timeout = 20000) {
  const want = normalize(text)
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const clicked = await page.evaluate((w) => {
      const nodes = [...document.querySelectorAll('button')]
        .filter((b) => !b.disabled && b.offsetParent !== null)
        .filter((b) => b.innerText.replace(/\s+/g, '').includes(w))
      const el = nodes[nodes.length - 1]
      if (!el) return false
      el.click()
      return true
    }, want)
    if (clicked) return true
    await new Promise((r) => setTimeout(r, 200))
  }
  throw new Error(`点不到按钮「${text}」（${timeout}ms 内都没出现在可点状态）`)
}

/** 按 placeholder 填值：React 受控组件只能用原生 setter + input 事件改，直接改 value 不触发 onChange */
async function fillByPlaceholder(page, scope, placeholder, value) {
  const ok = await page.evaluate(
    (sc, ph, v) => {
      const el = [...document.querySelectorAll(`${sc} input, ${sc} textarea`)].find(
        (i) => (i.placeholder || '').startsWith(ph) || (ph === '' && i.placeholder === ''),
      )
      if (!el) return false
      const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v)
      el.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    },
    scope,
    placeholder,
    value,
  )
  if (!ok) throw new Error(`填不到 placeholder「${placeholder}」`)
}

/** 菜单是 <a> 链接，单独走一条 */
async function clickNav(page, text) {
  const want = normalize(text)
  const ok = await page.evaluate((w) => {
    const el = [...document.querySelectorAll('.ant-menu a')].find((a) => a.innerText.replace(/\s+/g, '').includes(w))
    if (!el) return false
    el.click()
    return true
  }, want)
  if (!ok) throw new Error(`点不到菜单「${text}」`)
}

async function waitText(page, text, timeout = 20000) {
  await page.waitForFunction((t) => document.body.innerText.includes(t), { timeout }, text)
}

async function bodyText(page) {
  return page.evaluate(() => document.body.innerText)
}

async function textOf(page, selector) {
  return page.evaluate((s) => {
    const el = document.querySelector(s)
    return el ? el.innerText : null
  }, selector)
}

// ---------------------------------------------------------------- 主流程
;(async () => {
  if (!fs.existsSync(path.join(DIST, 'index.html'))) {
    fail(`缺少 ${DIST}/index.html，先跑：cd frontend && npm run build`)
  }
  let puppeteer
  try {
    puppeteer = require('puppeteer-core')
  } catch {
    fail('缺 puppeteer-core，先跑：cd frontend && npm i -D puppeteer-core（并用 NODE_PATH 指到 frontend/node_modules）')
  }
  const executablePath = CHROME_CANDIDATES.find((p) => fs.existsSync(p))
  if (!executablePath) fail('没找到 Chrome/Edge，可用 CHROME_PATH 指定')

  const server = http.createServer((req, res) => {
    if (req.url.startsWith('/api')) proxy(req, res)
    else serveStatic(req, res)
  })
  await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
  console.log(`静态服务 http://127.0.0.1:${PORT} → 后端 ${BACKEND}`)

  const browser = await puppeteer.launch({
    executablePath,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1600,1000'],
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })

  const pageErrors = []
  const failedRequests = []
  page.on('pageerror', (e) => pageErrors.push(String(e)))
  page.on('console', (m) => {
    if (m.type() === 'error') pageErrors.push('console: ' + m.text())
  })
  // 资源级失败单独收：console 里那条「Failed to load resource」不带 URL，
  // 想区分「报告 iframe 的 ECharts CDN 拿不到（离线正常）」和「我们自己站内资源挂了」只能看这里
  page.on('requestfailed', (r) => failedRequests.push({ url: r.url(), err: (r.failure() || {}).errorText }))

  // 先打开页面（about:blank 上 fetch 会被当成跨域），再通过同源反代探活
  await page.goto(BASE + '/', { waitUntil: 'networkidle2', timeout: 30000 })

  const health = await page.evaluate(async () => {
    const r = await fetch('/api/health/metadata')
    return r.ok ? r.json() : null
  })
  if (!health) fail(`后端不可达（经 ${BASE}/api 反代到 ${BACKEND}）`)
  const writeEnabled = health.writeEnabled !== false
  console.log(`后端在线：指标 ${health.metricCount} 条 / 字段 ${health.fieldCount} 条，写接口 ${writeEnabled ? '已开' : '关闭'}`)

  await waitText(page, '后端在线')

  // ---- 1. 页面骨架 ----
  const navText = await textOf(page, '.ant-layout-sider')
  check('页面骨架：四个菜单齐全', ['问数', '指标管理', '元数据', '闸门配置'].every((t) => (navText || '').includes(t)), navText?.replace(/\s+/g, ' '))
  check('顶栏探活：后端在线 + 写开关状态与后端一致', (await bodyText(page)).includes(writeEnabled ? '写接口已开' : '写接口关闭'))

  // ---- 2. 问数页：直接问 ----
  // 注意：判据必须落在「这次请求才可能产生的内容」上。
  // 第一版脚本等的是「结果」「五道闸门」—— 那是两个常驻文案，点完按钮立刻为真，
  // 于是所有断言读到的是渲染完成前的 DOM，8 条一起红。等具体数值才等得住。
  await clickButton(page, '直接问')
  await waitText(page, 'gmv_channel = 15604.97', 40000)
  const afterAsk = await bodyText(page)
  check('问数页：状态显示「成功」', afterAsk.includes('成功'))
  check('问数页：answer 是模板生成的一句话（gmv_channel = 15604.97）', /gmv_channel\s*=\s*15604\.97/.test(afterAsk), (afterAsk.match(/gmv_channel[^\n]*/) || [''])[0])
  check('问数页：结果表渲染出数据行', afterAsk.includes('15604.97'))

  // 五道闸门逐条列出（存在性/权限/代价/语法/结果自检）
  const gateRows = await page.evaluate(() => {
    const want = ['存在性', '权限', '代价', '语法', '结果自检']
    // 闸门名是 Typography.Text 渲染的 <span>，不是 div —— 按标签写死会漏
    const all = [...document.querySelectorAll('span, div')].map((e) => (e.innerText || '').trim())
    return want.filter((g) => all.includes(g))
  })
  check('链路证据：五道闸门逐条列出', gateRows.length === 5, gateRows.join('/'))

  // 参数化：SQL 文本里不该出现具体值 —— 值只在 params 里
  const blocks = await page.evaluate(() =>
    [...document.querySelectorAll('pre.code-block')].map((p) => p.innerText),
  )
  const sqlBlock = blocks[0] || ''
  check('参数化：SQL 含 SELECT/FROM 且不含具体值 15604.97', /SELECT/i.test(sqlBlock) && /FROM/i.test(sqlBlock) && !sqlBlock.includes('15604.97'), sqlBlock.split('\n')[0])
  check('参数化：值只出现在绑定参数里（日期 + 小程序）', /小程序/.test(blocks[1] || '') && /2026-10-19/.test(blocks[1] || ''), (blocks[1] || '').replace(/\s+/g, ' '))
  check('IR 回显：面板里有 metricId', blocks.some((b) => /"metricId"/.test(b)))

  // 报告：独立 iframe（sandbox）里是完整 HTML，含 echarts 与 <table>
  const reportHtml = await page.evaluate(() => {
    const f = document.querySelector('iframe.report-frame')
    return f ? f.srcdoc : ''
  })
  check('报告：iframe 里是完整 HTML（含 echarts 与 <table>）', /echarts/.test(reportHtml) && /<table>/i.test(reportHtml), `${reportHtml.length} chars`)

  // ---- 3. HITL：预览 → 改值 → 执行 ----
  await clickButton(page, '先预览')
  await waitText(page, '预览确认（这一步还没有执行）', 30000)
  const drawerText = await textOf(page, '.ant-drawer-body')
  check('HITL：抽屉打开且说明「只做校验不碰库」', (drawerText || '').includes('只做校验'))
  check('HITL：抽屉里有预览 SQL 与闸门结论', (drawerText || '').includes('SELECT') && (drawerText || '').includes('PASS'))

  // 把渠道值从「小程序」改成「APP」，点「执行改动」
  const changed = await page.evaluate(() => {
    const inputs = [...document.querySelectorAll('.ant-drawer-body input.ant-input')]
    const target = inputs.find((i) => i.value === '小程序')
    return target ? true : false
  })
  check('HITL：抽屉里能定位到渠道过滤值（小程序）', changed)
  if (changed) {
    // 用原生 setter + input 事件写值：这是 React 受控组件唯一可靠的改法。
    // 第一版用「三重点击 + 输入」，实测光标没选中原文，得到的是「APP小程序」——
    // 那种「改了一半」的值会被后端当成另一个渠道，断言就变成假绿/假红。
    await page.evaluate(() => {
      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
      const t = [...document.querySelectorAll('.ant-drawer-body input.ant-input')].find((i) => i.value === '小程序')
      setter.call(t, 'APP')
      t.dispatchEvent(new Event('input', { bubbles: true }))
    })
    await new Promise((r) => setTimeout(r, 200))
    await clickButton(page, '执行改动')
    await waitText(page, '服务端确认生效的编辑', 30000)
    const afterExec = await bodyText(page)
    check('HITL：执行后服务端确认「值变更」', /值变更/.test(afterExec) && /APP/.test(afterExec))
    check('HITL：改值真的改变了结果（15604.97 → 20324.53）', /20324\.53/.test(afterExec), (afterExec.match(/gmv_channel[^\n]*/) || [''])[0])

    // 服务端 diff 是权威：库里这一轮应该是「转了 APP」
    const traceId = await page.evaluate(() => {
      const t = document.body.innerText
      const m = t.match(/traceId\s*([0-9a-zA-Z-]{8,})/)
      return m ? m[1] : null
    })
    check('HITL：结果回显 traceId（可去 /api/traces 复查）', !!traceId, traceId || '-')
  }

  // ---- 4. 指标管理页 ----
  await clickNav(page, '指标管理')
  await waitText(page, 'gmv_total', 20000) // 等数据行，不等标题
  const metricsText = await bodyText(page)
  check('指标页：列表渲染出指标', /gmv_/.test(metricsText) && /SUM\(/.test(metricsText))

  // 负向：口径写非法 SQL → 后端 400，页面显示原因（不写库、零污染）
  await clickButton(page, '新增指标')
  await waitText(page, '新增指标', 20000)
  // 主表也必须填：后端的 @Valid 在口径校验之前跑，缺 tableName 会先回「tableName 不能为空」，
  // 那样测到的就不是「口径白名单」这条规矩了（第一版就栽在这）。
  await fillByPlaceholder(page, '.ant-modal', 'gmv_total', 'zz_smoke_bad')
  await fillByPlaceholder(page, '.ant-modal', '总GMV', '冒烟非法口径')
  await fillByPlaceholder(page, '.ant-modal', 'SUM(total_gmv)', 'DROP TABLE orders')
  await fillByPlaceholder(page, '.ant-modal', 'ads_trade_overview_di', 'ads_channel_conversion_di')
  await new Promise((r) => setTimeout(r, 300))
  // 写接口开着 → 应该测到「口径白名单」这条规矩；
  // 写接口关闭 → 请求先被 403 拦在门口，此时该断言的是「关闭态确实拒绝了写」。
  // 两种都写，是因为默认配置就是关闭态（Ticket 14 接鉴权前的兜底）。
  const expectMsg = writeEnabled ? 'metric_sql 非法' : '写接口已关闭'
  await clickButton(page, '保存')
  await waitText(page, expectMsg, 20000)
  check(
    `指标页：非法口径${writeEnabled ? '被口径白名单拒绝' : '被写开关拒绝'}且原因透传到页面`,
    (await bodyText(page)).includes(expectMsg),
  )
  await clickButton(page, '取消')
  await new Promise((r) => setTimeout(r, 500))

  // ---- 5. 元数据 / 白名单页 ----
  await clickNav(page, '元数据')
  // 等真实表名，而不是等卡片标题 —— 标题在数据回来之前就已经渲染出来了
  await waitText(page, 'ads_trade_overview_di', 20000)
  const metaText = await bodyText(page)
  check('元数据页：表清单与字段白名单都有内容', /ads_|dwd_|dim_/.test(metaText) && /字段名/.test(metaText))
  check('元数据页：写开关状态与后端一致', writeEnabled ? !metaText.includes('写接口当前关闭') : metaText.includes('写接口当前关闭'))

  if (writeEnabled) {
    // 真写一条白名单字段，再从页面摘掉 —— 闭环验证 PUT + DELETE 且不留残留
    await clickButton(page, '新增字段')
    await waitText(page, '新增字段', 20000)
    await fillByPlaceholder(page, '.ant-modal', 'channel_name', 'zz_smoke_field')
    await fillByPlaceholder(page, '.ant-modal', 'varchar(32)', 'varchar(16)')
    await fillByPlaceholder(page, '.ant-modal', '渠道名称', '冒烟测试字段')
    await new Promise((r) => setTimeout(r, 300))
    await clickButton(page, '保存')
    await waitText(page, '已写入白名单', 20000)
    await new Promise((r) => setTimeout(r, 500))
    const afterPut = await bodyText(page)
    check('元数据页：新增字段落库并出现在白名单表格', afterPut.includes('zz_smoke_field'))

    // 外部核对一遍（不信页面自己说成功了）
    const viaApi = await page.evaluate(async () => {
      const r = await fetch('/api/metadata/fields')
      const list = await r.json()
      return list.some((f) => f.fieldName === 'zz_smoke_field')
    })
    check('元数据页：接口侧确实存在该字段（页面没说谎）', viaApi)

    await page.evaluate(() => {
      const rows = [...document.querySelectorAll('.ant-table-row')]
      const row = rows.find((r) => r.innerText.includes('zz_smoke_field'))
      if (row) {
        const btn = [...row.querySelectorAll('button')].find((b) => b.innerText.includes('移出'))
        if (btn) btn.click()
      }
    })
    await new Promise((r) => setTimeout(r, 400))
    await clickButton(page, '摘掉')
    await waitText(page, '已摘掉', 20000)
    await new Promise((r) => setTimeout(r, 500))
    const viaApiAfter = await page.evaluate(async () => {
      const r = await fetch('/api/metadata/fields')
      const list = await r.json()
      return list.some((f) => f.fieldName === 'zz_smoke_field')
    })
    check('元数据页：移出白名单后接口侧已消失（无残留）', viaApiAfter === false)
  }

  // ---- 6. 闸门配置页 ----
  await clickNav(page, '闸门配置')
  // 同上：等参数本身，别等卡片标题（标题先渲染，数据后到）
  await waitText(page, 'max_join', 20000)
  const gatePage = await bodyText(page)
  check('闸门页：四个阈值参数齐全', ['max_join', 'max_metric', 'max_limit', 'max_rows'].every((k) => gatePage.includes(k)))

  // ---- 7. 全程没有前端异常 ----
  // 两类是「预期内的噪音」，不算前端缺陷：
  //   ① 指标页负向用例故意打的 400（不做这条就测不到口径白名单）；
  //   ② 报告 iframe 里 ECharts 的 CDN 脚本（离线环境取不到 jsdelivr）。
  // 其余任何 console error / pageerror 都算失败 —— React 崩溃、字段 undefined 都会在这里现形。
  const expectedNoise = (e) => /status of 400/.test(e) || /Failed to load resource/.test(e) || /jsdelivr/.test(e)
  const realErrors = pageErrors.filter((e) => !expectedNoise(e))
  check('浏览器控制台无意外报错（React/组件异常会在这里现形）', realErrors.length === 0, realErrors.slice(0, 3).join(' | '))

  const siteFailed = failedRequests.filter((r) => r.url.startsWith(BASE))
  check('站内资源请求全部成功（dist 打包/路由回退/反代都在这一条上）', siteFailed.length === 0,
    siteFailed.slice(0, 3).map((r) => r.url.replace(BASE, '') + ' ' + r.err).join(' | '))

  await browser.close()
  server.close()

  console.log(`\n合计 ${checks.length} 项，失败 ${failed} 项`)
  process.exit(failed === 0 ? 0 : 1)
})().catch((e) => {
  console.error('冒烟脚本异常：', e)
  process.exit(1)
})
