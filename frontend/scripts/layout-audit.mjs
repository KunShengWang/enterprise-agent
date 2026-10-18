#!/usr/bin/env node
/**
 * 布局审计 —— 用 Chrome DevTools Protocol 检测每个页面在每个断点下是否横向溢出。
 *
 * 为什么需要它：jsdom smoke 不跑布局，截图只能肉眼看，而"375px 会不会横向溢出"
 * 正是上一轮挂了未完成的验收项。CDP 的 Runtime.evaluate 能直接拿到
 * documentElement.scrollWidth 与每个越界元素的边界，给出可复现的判据。
 *
 * 零依赖：Node 22+ 内置 WebSocket，不引入 puppeteer/playwright。
 *
 * 用法：
 *   node scripts/layout-audit.mjs
 *   node scripts/layout-audit.mjs --label before-w1 --only workbench
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const FRONTEND = resolve(HERE, '..')

const CHROME_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
]

const PAGES = [
  { name: 'workbench', hash: '#/' },
  { name: 'runs', hash: '#/runs' },
  { name: 'approvals', hash: '#/approvals' },
  { name: 'capabilities', hash: '#/capabilities' },
  { name: 'knowledge', hash: '#/knowledge' },
  { name: 'observability', hash: '#/observability' },
  { name: 'api-lab', hash: '#/api-lab' },
  { name: 'retired-business', hash: '#/incident-command' },
  { name: 'settings', hash: '#/settings' },
]

const VIEWPORTS = [
  { name: 'desktop', width: 1280, height: 720 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'mobile', width: 375, height: 812 },
]

/** 容差：亚像素舍入会让 right 比 clientWidth 大 0.x。 */
const TOLERANCE = 1

const AUDIT_SCRIPT = `(() => {
  const de = document.documentElement;
  const clientWidth = de.clientWidth;
  const scrollWidth = de.scrollWidth;
  const offenders = [];
  for (const el of document.querySelectorAll('*')) {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) continue;
    if (r.right > clientWidth + ${TOLERANCE}) {
      const cls = typeof el.className === 'string' ? el.className : (el.getAttribute('class') || '');
      offenders.push({
        tag: el.tagName.toLowerCase(),
        cls: cls.slice(0, 80),
        right: Math.round(r.right * 100) / 100,
        width: Math.round(r.width * 100) / 100,
      });
    }
  }
  offenders.sort((a, b) => b.right - a.right);
  return {
    clientWidth,
    scrollWidth,
    overflowX: scrollWidth - clientWidth,
    bodyScrollWidth: document.body ? document.body.scrollWidth : -1,
    offenders: offenders.slice(0, 8),
  };
})()`

function parseArgs(argv) {
  const out = { label: 'layout-audit', base: 'http://127.0.0.1:5174', only: null, port: 9333 }
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i]
    const next = argv[i + 1]
    if (key === '--label') { out.label = next; i += 1 }
    else if (key === '--base') { out.base = next; i += 1 }
    else if (key === '--only') { out.only = next.split(',').map(s => s.trim()).filter(Boolean); i += 1 }
    else if (key === '--port') { out.port = Number(next); i += 1 }
  }
  return out
}

function findBrowser() {
  for (const candidate of CHROME_CANDIDATES) if (existsSync(candidate)) return candidate
  return null
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

async function waitForDevTools(port, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`)
      if (res.ok) return await res.json()
    } catch { /* 还没起来 */ }
    await sleep(200)
  }
  throw new Error(`DevTools 在 ${timeoutMs}ms 内未就绪（端口 ${port}）`)
}

function makeClient(ws) {
  let seq = 0
  const pending = new Map()
  const waiters = new Map()
  ws.addEventListener('message', event => {
    const msg = JSON.parse(event.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve: res, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      if (msg.error) reject(new Error(JSON.stringify(msg.error)))
      else res(msg.result)
      return
    }
    if (msg.method && waiters.has(msg.method)) {
      const list = waiters.get(msg.method)
      waiters.delete(msg.method)
      for (const fn of list) fn(msg.params)
    }
  })
  return {
    send(method, params = {}) {
      const id = ++seq
      return new Promise((res, reject) => {
        pending.set(id, { resolve: res, reject })
        ws.send(JSON.stringify({ id, method, params }))
      })
    },
    once(method) {
      return new Promise(res => {
        if (!waiters.has(method)) waiters.set(method, [])
        waiters.get(method).push(res)
      })
    },
  }
}

async function auditOne(browser, base, port, page, viewport) {
  const created = await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })
  if (!created.ok) throw new Error(`创建 target 失败: HTTP ${created.status}`)
  const target = await created.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, reject) => {
    ws.addEventListener('open', res, { once: true })
    ws.addEventListener('error', () => reject(new Error('WebSocket 连接失败')), { once: true })
  })
  const client = makeClient(ws)
  try {
    await client.send('Page.enable')
    await client.send('Runtime.enable')
    await client.send('Emulation.setDeviceMetricsOverride', {
      width: viewport.width, height: viewport.height, deviceScaleFactor: 1, mobile: false,
    })
    const loaded = client.once('Page.loadEventFired')
    await client.send('Page.navigate', { url: `${base}/${page.hash}` })
    await Promise.race([loaded, sleep(8000)])
    // 给 Vue 挂载与异步数据留时间；不阻塞在永远失败的后端请求上。
    await sleep(1200)
    const result = await client.send('Runtime.evaluate', { expression: AUDIT_SCRIPT, returnByValue: true })
    return result.result.value
  } finally {
    try { ws.close() } catch { /* ignore */ }
    await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`).catch(() => {})
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  const browser = findBrowser()
  if (!browser) { console.error('未找到 Chrome 或 Edge。'); process.exit(3) }

  const pages = args.only ? PAGES.filter(p => args.only.includes(p.name)) : PAGES
  if (!pages.length) { console.error(`--only 未匹配。可用：${PAGES.map(p => p.name).join(', ')}`); process.exit(2) }

  const userDataDir = join(FRONTEND, 'node_modules', '.cache', 'layout-audit-profile')
  mkdirSync(userDataDir, { recursive: true })

  const child = spawn(browser, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    '--force-device-scale-factor=1',
    `--remote-debugging-port=${args.port}`,
    `--user-data-dir=${userDataDir}`,
    'about:blank',
  ], { stdio: 'ignore' })

  const report = { label: args.label, base: args.base, takenAt: new Date().toISOString(), results: [] }
  let exitCode = 0
  try {
    await waitForDevTools(args.port)
    for (const page of pages) {
      for (const viewport of VIEWPORTS) {
        const r = await auditOne(browser, args.base, args.port, page, viewport)
        const overflow = r.overflowX > TOLERANCE
        if (overflow) exitCode = 1
        report.results.push({ page: page.name, viewport: viewport.name, width: viewport.width, ...r, overflow })
        const mark = overflow ? '✗ 溢出' : '✓'
        console.log(`${mark} ${page.name.padEnd(17)} ${viewport.name.padEnd(8)} client=${r.clientWidth} scroll=${r.scrollWidth} 溢出=${r.overflowX}px`)
        if (overflow && r.offenders.length) {
          for (const o of r.offenders) {
            console.log(`     └ <${o.tag} class="${o.cls}"> right=${o.right} width=${o.width}`)
          }
        }
      }
    }
  } finally {
    child.kill()
  }

  const outDir = join(FRONTEND, 'docs', 'screenshots', args.label)
  mkdirSync(outDir, { recursive: true })
  writeFileSync(join(outDir, 'layout-audit.json'), JSON.stringify(report, null, 2), 'utf8')

  const overflowing = report.results.filter(r => r.overflow)
  console.log(`\n共 ${report.results.length} 组合，横向溢出 ${overflowing.length} 组`)
  if (overflowing.length) {
    process.exit(1)
  }
  process.exit(exitCode)
}

main().catch(err => { console.error(err); process.exit(1) })
