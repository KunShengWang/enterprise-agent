#!/usr/bin/env node
/**
 * 表面审计 —— 在指定主题下找出「仍然亮着」的背景块与「看不清」的文字。
 *
 * 为什么需要它：`--force-device-scale-factor` 解决了截图尺寸，但截图只能靠肉眼看。
 * 深色主题真正的风险是 327 处硬编码背景色（styles.css）在深色下变成白块——
 * 这里用 getComputedStyle 把每一处量化出来，既是验收判据，也是迁移工作清单。
 *
 * 判据：
 *   - 浅色背景残余：alpha > 0.05 且相对亮度 > 200，面积 > 400px²
 *   - 低对比文字：文字色亮度与自身背景亮度差 < 60（深色下常见于硬编码 #202123）
 *
 * 用法：
 *   node scripts/contrast-audit.mjs --label w1-dark --theme dark
 *   node scripts/contrast-audit.mjs --label before-w1 --theme light --only workbench
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
]

const VIEWPORTS = [
  { name: 'desktop', width: 1280, height: 720 },
  { name: 'mobile', width: 375, height: 812 },
]

const LIGHT_BG_LUMINANCE = 200
const LIGHT_BG_MIN_AREA = 400
const CONTRAST_MIN_DELTA = 60

const AUDIT_SCRIPT = `(() => {
  const LIGHT_BG_LUMINANCE = ${LIGHT_BG_LUMINANCE};
  const LIGHT_BG_MIN_AREA = ${LIGHT_BG_MIN_AREA};
  const CONTRAST_MIN_DELTA = ${CONTRAST_MIN_DELTA};

  const parse = value => {
    const m = String(value).match(/rgba?\\(([^)]+)\\)/);
    if (!m) return null;
    const parts = m[1].split(',').map(s => parseFloat(s.trim()));
    if (parts.length < 3) return null;
    const [r, g, b] = parts;
    const a = parts.length > 3 ? parts[3] : 1;
    return { r, g, b, a, lum: 0.2126 * r + 0.7152 * g + 0.0722 * b };
  };

  const label = el => {
    const cls = typeof el.className === 'string' ? el.className : (el.getAttribute('class') || '');
    return '<' + el.tagName.toLowerCase() + (cls ? ' class="' + cls.slice(0, 70) + '"' : '') + '>';
  };

  const lightSurfaces = [];
  const lowContrast = [];

  for (const el of document.querySelectorAll('*')) {
    const rect = el.getBoundingClientRect();
    if (rect.width < 2 || rect.height < 2) continue;
    const cs = getComputedStyle(el);
    const bg = parse(cs.backgroundColor);

    if (bg && bg.a > 0.05 && bg.lum > LIGHT_BG_LUMINANCE && rect.width * rect.height > LIGHT_BG_MIN_AREA) {
      lightSurfaces.push({ el: label(el), bg: cs.backgroundColor, area: Math.round(rect.width * rect.height) });
    }

    // 只看有直接文本的叶节点，避免父容器重复计数
    const hasOwnText = [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim().length > 0);
    if (!hasOwnText) continue;
    const fg = parse(cs.color);
    if (!fg || fg.a < 0.05) continue;
    // 沿祖先链找最近的不透明背景
    let base = null;
    for (let node = el; node; node = node.parentElement) {
      const candidate = parse(getComputedStyle(node).backgroundColor);
      if (candidate && candidate.a > 0.5) { base = candidate; break; }
    }
    if (!base) continue;
    const delta = Math.abs(fg.lum - base.lum);
    if (delta < CONTRAST_MIN_DELTA) {
      lowContrast.push({ el: label(el), color: cs.color, background: 'rgb(' + base.r + ',' + base.g + ',' + base.b + ')', delta: Math.round(delta), text: (el.textContent || '').trim().slice(0, 40) });
    }
  }

  lightSurfaces.sort((a, b) => b.area - a.area);
  return {
    theme: document.documentElement.dataset.theme || '(none)',
    lightSurfaceCount: lightSurfaces.length,
    lightSurfaces: lightSurfaces.slice(0, 15),
    lowContrastCount: lowContrast.length,
    lowContrast: lowContrast.slice(0, 10),
  };
})()`

function parseArgs(argv) {
  const out = { label: 'contrast', theme: 'dark', base: 'http://127.0.0.1:5174', only: null, port: 9334 }
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i]
    const next = argv[i + 1]
    if (key === '--label') { out.label = next; i += 1 }
    else if (key === '--theme') { out.theme = next; i += 1 }
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
    } catch { /* not ready */ }
    await sleep(200)
  }
  throw new Error(`DevTools 未就绪（端口 ${port}）`)
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

async function auditOne(port, url, viewport) {
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
    await client.send('Page.navigate', { url })
    await Promise.race([loaded, sleep(8000)])
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

  const userDataDir = join(FRONTEND, 'node_modules', '.cache', 'contrast-audit-profile')
  mkdirSync(userDataDir, { recursive: true })

  const child = spawn(browser, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    '--force-device-scale-factor=1',
    `--remote-debugging-port=${args.port}`,
    `--user-data-dir=${userDataDir}`,
    'about:blank',
  ], { stdio: 'ignore' })

  const report = { label: args.label, theme: args.theme, base: args.base, takenAt: new Date().toISOString(), results: [] }
  try {
    await waitForDevTools(args.port)
    for (const page of pages) {
      for (const viewport of VIEWPORTS) {
        const url = `${args.base}/?theme=${args.theme}${page.hash}`
        const r = await auditOne(args.port, url, viewport)
        report.results.push({ page: page.name, viewport: viewport.name, ...r })
        const mark = r.lightSurfaceCount === 0 ? '✓' : '✗'
        console.log(`${mark} ${page.name.padEnd(17)} ${viewport.name.padEnd(8)} theme=${r.theme} 浅色残块=${r.lightSurfaceCount} 低对比文字=${r.lowContrastCount}`)
        for (const s of r.lightSurfaces.slice(0, 4)) {
          console.log(`     └ ${s.el} bg=${s.bg} area=${s.area}`)
        }
      }
    }
  } finally {
    child.kill()
  }

  const outDir = join(FRONTEND, 'docs', 'screenshots', args.label)
  mkdirSync(outDir, { recursive: true })
  writeFileSync(join(outDir, `contrast-${args.theme}.json`), JSON.stringify(report, null, 2), 'utf8')

  const totalLight = report.results.reduce((n, r) => n + r.lightSurfaceCount, 0)
  const totalLow = report.results.reduce((n, r) => n + r.lowContrastCount, 0)
  console.log(`\n共 ${report.results.length} 组合：浅色残块 ${totalLight} 处，低对比文字 ${totalLow} 处`)
}

main().catch(err => { console.error(err); process.exit(1) })
