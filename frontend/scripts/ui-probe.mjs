#!/usr/bin/env node
/**
 * 交互探针 —— 在真实浏览器里执行一段 JS（可点击、可等待），把结果打印出来。
 *
 * 为什么需要它：jsdom smoke 不跑布局也不派发真实事件，截图只能看静态结果，
 * 而「点击『更多』后菜单是否真的展开 6 项」这类断言两样都覆盖不到。
 * 这里用 CDP 的 Runtime.evaluate(awaitPromise) 直接驱动页面并取回结构化结果。
 *
 * 零依赖：Node 22+ 内置 WebSocket。
 *
 * 用法：
 *   node scripts/ui-probe.mjs --url "http://127.0.0.1:5174/#/approvals" \
 *     --expr "(async()=>{const b=document.querySelector('.nav-more-toggle');b.click();await new Promise(r=>setTimeout(r,120));return {expanded:b.getAttribute('aria-expanded'),items:[...document.querySelectorAll('#sidebar-more-nav a')].map(a=>a.textContent.trim())}})()"
 *
 *   node scripts/ui-probe.mjs --url ".../#/settings" --expr "document.querySelectorAll('.theme-option').length"
 *
 * 退出码：探针本身成功即 0（结果内容由调用方判断）；脚本/连接失败为 1。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync } from 'node:fs'
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

function parseArgs(argv) {
  const out = { url: '', expr: '', width: 1280, height: 800, port: 9335, wait: 1200 }
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i]
    const next = argv[i + 1]
    if (key === '--url') { out.url = next; i += 1 }
    else if (key === '--expr') { out.expr = next; i += 1 }
    else if (key === '--width') { out.width = Number(next); i += 1 }
    else if (key === '--height') { out.height = Number(next); i += 1 }
    else if (key === '--port') { out.port = Number(next); i += 1 }
    else if (key === '--wait') { out.wait = Number(next); i += 1 }
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

async function main() {
  const args = parseArgs(process.argv.slice(2))
  if (!args.url || !args.expr) {
    console.error('需要 --url 与 --expr')
    process.exit(2)
  }
  const browser = findBrowser()
  if (!browser) { console.error('未找到 Chrome 或 Edge。'); process.exit(3) }

  const userDataDir = join(FRONTEND, 'node_modules', '.cache', 'ui-probe-profile')
  mkdirSync(userDataDir, { recursive: true })

  const child = spawn(browser, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    '--force-device-scale-factor=1',
    `--remote-debugging-port=${args.port}`,
    `--user-data-dir=${userDataDir}`,
    'about:blank',
  ], { stdio: 'ignore' })

  try {
    await waitForDevTools(args.port)
    const created = await fetch(`http://127.0.0.1:${args.port}/json/new?about:blank`, { method: 'PUT' })
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
        width: args.width, height: args.height, deviceScaleFactor: 1, mobile: false,
      })
      const loaded = client.once('Page.loadEventFired')
      await client.send('Page.navigate', { url: args.url })
      await Promise.race([loaded, sleep(8000)])
      await sleep(args.wait)

      const result = await client.send('Runtime.evaluate', {
        expression: args.expr,
        returnByValue: true,
        awaitPromise: true,
      })
      if (result.exceptionDetails) {
        console.error('页面内执行抛错:', JSON.stringify(result.exceptionDetails, null, 2))
        process.exit(1)
      }
      console.log(JSON.stringify(result.result.value, null, 2))
    } finally {
      try { ws.close() } catch { /* ignore */ }
      await fetch(`http://127.0.0.1:${args.port}/json/close/${target.id}`).catch(() => {})
    }
  } finally {
    child.kill()
  }
}

main().catch(err => { console.error(err); process.exit(1) })
