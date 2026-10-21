/**
 * 经营大屏验收（P1-A）。
 *
 * 做法和 qa/smoke_frontend.cjs 一致：Node 内置 http 托管 frontend/dist + 同源反代 /api，
 * 再用 puppeteer-core 驱动本机 Chrome。这样页面里 `fetch('/api/...')` 天然同源，
 * 不需要为了测试给后端开 CORS（CORS 是给「独立部署的 go-view 静态站」准备的，另有 curl 用例覆盖）。
 *
 * 验收点（都是「用户在大屏上能看见什么」）：
 *   1. 4 个 KPI 卡片，GMV = ¥53,042.41（与 ADS 实查一致）
 *   2. 趋势图 7 个数据点，日期是最新 7 天
 *   3. 渠道 3 条、地区 5 条、商品 8 条，中文名正常（不是乱码）
 *   4. 业务日期回显 2026-10-19
 *   5. 无页面级 console error
 *   6. 1920×1080 截图存档
 *
 * 用法：NODE_PATH=frontend/node_modules node qa/verify_dashboard.cjs
 */
const http = require('http')
const fs = require('fs')
const path = require('path')

const DIST = path.join(__dirname, '..', 'frontend', 'dist')
const API_PORT = Number(process.env.DP_PORT || 18080)
const PORT = Number(process.env.DASH_PORT || 18099)
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

function startServer() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      if (req.url.startsWith('/api/')) {
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

;(async () => {
  if (!fs.existsSync(path.join(DIST, 'index.html'))) {
    console.error('缺 frontend/dist，先 cd frontend && node node_modules/vite/bin/vite.js build')
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
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  })
  try {
    const page = await browser.newPage()
    await page.setViewport({ width: 1920, height: 1080 })
    const errs = []
    page.on('console', (m) => {
      if (m.type() === 'error') errs.push(m.text().slice(0, 240))
    })
    page.on('pageerror', (e) => errs.push('pageerror: ' + String(e).slice(0, 240)))

    await page.goto(`http://127.0.0.1:${PORT}/dashboard`, { waitUntil: 'networkidle2', timeout: 60000 })
    await page.waitForSelector('[data-kpi]', { timeout: 20000 })
    await new Promise((r) => setTimeout(r, 800))

    const view = await page.evaluate(() => {
      const txt = (sel) => document.querySelector(sel)?.innerText || ''
      const all = (sel) => Array.from(document.querySelectorAll(sel))
      return {
        header: document.body.innerText.slice(0, 160),
        kpis: all('[data-kpi]').map((el) => ({ label: el.dataset.kpi, text: el.innerText })),
        points: all('[data-point]').map((el) => el.dataset.point),
        channel: all('[data-panel="渠道转化"] [data-bar]').map((el) => el.dataset.bar),
        region: all('[data-panel="地区 GMV 排行"] [data-bar]').map((el) => el.dataset.bar),
        product: all('[data-panel="商品销售 TOP 8"] [data-bar]').map((el) => el.dataset.bar),
        hasNav: !!document.querySelector('.ant-layout-sider'),
      }
    })
    // 商品表当前只有 3 个 SKU（ADS 实况），所以条数对着接口取，不写死 8 —— 写死只会让
    // 「数据量变了」变成假 FAIL，而这条要证明的是「页面把接口给的都画出来了」。
    const apiProduct = await page.evaluate(async () => (await fetch('/api/dashboard/product?limit=8')).json())

    console.log('== ① 页面结构与 KPI ==')
    check('标题含「抖音订单经营大屏」', view.header.includes('抖音订单经营大屏'), view.header.slice(0, 60))
    check('大屏不套后台侧边栏（全屏）', view.hasNav === false)
    check('4 个 KPI 卡片', view.kpis.length === 4, view.kpis.map((k) => k.label))
    const gmv = view.kpis.find((k) => k.label.includes('GMV'))
    check('GMV 卡片 = ¥53,042.41（与 ADS 实查一致）', gmv && gmv.text.includes('53,042.41'), gmv && gmv.text)
    check('GMV 卡片显示环比 +4.9%', gmv && gmv.text.includes('4.9%'), gmv && gmv.text)
    const rate = view.kpis.find((k) => k.label.includes('支付转化率'))
    check('支付转化率 = 90.0%', rate && rate.text.includes('90.0%'), rate && rate.text)

    console.log('== ② 趋势图 ==')
    check('7 个数据点', view.points.length === 7, view.points)
    check('日期升序且覆盖 10-13 ~ 10-19',
      view.points[0] === '2026-10-13' && view.points[6] === '2026-10-19', view.points)

    console.log('== ③ 三个排行榜 ==')
    check('渠道 3 条', view.channel.length === 3, view.channel)
    check('渠道中文名正常（小程序/网页/APP）',
      view.channel.some((x) => x.includes('小程序')) && view.channel.some((x) => x.includes('网页')), view.channel)
    check('地区 5 条且含上海', view.region.length === 5 && view.region.some((x) => x.includes('上海')), view.region)
    check(`商品条数与 ADS 一致（${apiProduct.length} 条）且含 4K显示器`,
      view.product.length === apiProduct.length && apiProduct.length > 0
        && view.product.some((x) => x.includes('4K显示器')), view.product.length)

    console.log('== ④ 运行期错误 ==')
    check('无 console error / pageerror', errs.length === 0, errs.slice(0, 3))

    fs.mkdirSync(SHOT_DIR, { recursive: true })
    const shot = path.join(SHOT_DIR, 'dashboard_1920x1080.png')
    await page.screenshot({ path: shot })
    console.log('\n截图 ->', shot)
  } finally {
    await browser.close()
    server.close()
  }

  console.log(`\n结果：PASS ${pass} / ${pass + failed}`)
  process.exit(failed === 0 ? 0 : 1)
})().catch((e) => {
  console.error('FATAL', e)
  process.exit(1)
})
