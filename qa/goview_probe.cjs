/**
 * go-view 探针（一次性）：打开 http://localhost:8080，打印标题、主要按钮、控制台报错，并截图。
 * 目的：确认这版 go-view 能不能直接建大屏 / 导入 JSON，再决定自动化路径。
 */
const fs = require('fs')
const path = require('path')
let puppeteer
try {
  puppeteer = require('puppeteer-core')
} catch (e) {
  console.error('缺 puppeteer-core，需 NODE_PATH 指到 frontend/node_modules')
  process.exit(2)
}
const CHROME = [
  process.env.CHROME_PATH,
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
].find((p) => p && fs.existsSync(p))

const OUT = path.join(__dirname, '..', 'docs', 'screenshots')

;(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1600,1000'],
  })
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000 })
  const errs = []
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)) })
  page.on('pageerror', (e) => errs.push('pageerror: ' + String(e).slice(0, 200)))

  await page.goto('http://localhost:8080', { waitUntil: 'networkidle2', timeout: 60000 })
  await new Promise((r) => setTimeout(r, 2500))

  const info = await page.evaluate(() => ({
    title: document.title,
    url: location.href,
    text: document.body.innerText.slice(0, 800),
    buttons: Array.from(document.querySelectorAll('button, .btn, [class*=btn]'))
      .map((b) => (b.innerText || '').trim())
      .filter(Boolean)
      .slice(0, 40),
    version: (window.__GOVIEW_VERSION__ || document.querySelector('meta[name=version]')?.content) || null,
  }))
  console.log(JSON.stringify(info, null, 2))
  console.log('--- console errors ---')
  console.log(errs.slice(0, 10).join('\n') || '(none)')

  fs.mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: path.join(OUT, 'goview_probe_home.png'), fullPage: false })
  console.log('screenshot ->', path.join(OUT, 'goview_probe_home.png'))
  await browser.close()
})().catch((e) => { console.error('FATAL', e); process.exit(1) })
