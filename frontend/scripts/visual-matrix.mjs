#!/usr/bin/env node
/**
 * 视觉截图矩阵 —— 用系统 Chrome/Edge 的 headless 模式渲染每个页面 × 每个断点。
 *
 * 为什么不用 Playwright：本机已有 Chrome，`--headless --screenshot --window-size`
 * 就能让 viewport 真实生效（实测 375x812 / 1280x720 精确），零新增依赖、无浏览器下载。
 * 上一轮"viewport 设置不生效"的坑由 `--force-device-scale-factor=1` 解决
 * （否则 Windows DPI 缩放会把 375 渲染成 469）。
 *
 * 用法：
 *   node scripts/visual-matrix.mjs --label before-w1
 *   node scripts/visual-matrix.mjs --label w1 --theme dark
 *   node scripts/visual-matrix.mjs --label w1 --theme light --only workbench,api-lab
 *
 * 输出：docs/screenshots/<label>/<theme>/<page>-<viewport>.png + manifest.json
 */
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs'
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

/** 低于这个字节数基本是空白页（Chrome 输出的纯色图约 1-2KB）。 */
const MIN_BYTES = 3000

function parseArgs(argv) {
  const out = { label: '', theme: 'current', base: 'http://127.0.0.1:5174', only: null, budget: 4000 }
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i]
    const next = argv[i + 1]
    if (key === '--label') { out.label = next; i += 1 }
    else if (key === '--theme') { out.theme = next; i += 1 }
    else if (key === '--base') { out.base = next; i += 1 }
    else if (key === '--only') { out.only = next.split(',').map(s => s.trim()).filter(Boolean); i += 1 }
    else if (key === '--budget') { out.budget = Number(next); i += 1 }
  }
  return out
}

function findBrowser() {
  for (const candidate of CHROME_CANDIDATES) {
    if (existsSync(candidate)) return candidate
  }
  return null
}

function buildUrl(base, hash, theme) {
  // 主题通过 query 传递；页面用 URLSearchParams 读取并写入 localStorage。
  // theme=current 表示"该版本还没有主题能力"，不加参数。
  if (theme === 'current') return `${base}/${hash}`
  return `${base}/?theme=${encodeURIComponent(theme)}${hash}`
}

function shoot(browser, url, outPath, viewport, budget) {
  const args = [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--hide-scrollbars',
    // 不指定 --user-data-dir 时 Chrome 会复用默认 profile，可能命中磁盘缓存
    // 而截到改动前的页面；incognito 保证每次都是干净会话。
    '--incognito',
    '--force-device-scale-factor=1',
    '--disable-lcd-text',
    `--window-size=${viewport.width},${viewport.height}`,
    `--virtual-time-budget=${budget}`,
    `--screenshot=${outPath}`,
    url,
  ]
  execFileSync(browser, args, { stdio: 'pipe', timeout: 60000 })
}

function main() {
  const args = parseArgs(process.argv.slice(2))
  if (!args.label) {
    console.error('缺少 --label（输出目录名，如 before-w1 / w1-dark）')
    process.exit(2)
  }
  const browser = findBrowser()
  if (!browser) {
    console.error('未找到 Chrome 或 Edge，无法截图。')
    process.exit(3)
  }

  const pages = args.only ? PAGES.filter(p => args.only.includes(p.name)) : PAGES
  if (!pages.length) {
    console.error(`--only 未匹配任何页面。可用：${PAGES.map(p => p.name).join(', ')}`)
    process.exit(2)
  }

  const outDir = join(FRONTEND, 'docs', 'screenshots', args.label, args.theme)
  mkdirSync(outDir, { recursive: true })

  const manifest = { label: args.label, theme: args.theme, base: args.base, browser, takenAt: new Date().toISOString(), shots: [] }
  const warnings = []

  for (const page of pages) {
    for (const viewport of VIEWPORTS) {
      const fileName = `${page.name}-${viewport.name}.png`
      const outPath = join(outDir, fileName)
      const windowsPath = outPath.replaceAll('/', '\\')
      const url = buildUrl(args.base, page.hash, args.theme)
      const started = Date.now()
      let ok = true
      let error = ''
      try {
        shoot(browser, url, windowsPath, viewport, args.budget)
      } catch (err) {
        ok = false
        error = String(err?.message ?? err).slice(0, 300)
      }
      const bytes = ok && existsSync(outPath) ? statSync(outPath).size : 0
      if (ok && bytes < MIN_BYTES) {
        warnings.push(`${fileName} 仅 ${bytes} bytes，可能是空白页`)
      }
      manifest.shots.push({
        page: page.name, viewport: viewport.name, width: viewport.width, height: viewport.height,
        file: `${args.theme}/${fileName}`, bytes, ms: Date.now() - started, ok, error,
      })
      console.log(`${ok ? '✓' : '✗'} ${args.theme}/${fileName} (${bytes} bytes, ${Date.now() - started}ms)${error ? ' — ' + error : ''}`)
    }
  }

  mkdirSync(outDir, { recursive: true })
  writeFileSync(join(outDir, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8')

  const failed = manifest.shots.filter(s => !s.ok)
  console.log(`\n共 ${manifest.shots.length} 张，失败 ${failed.length} 张，警告 ${warnings.length} 条`)
  if (warnings.length) console.log('警告：\n  ' + warnings.join('\n  '))
  if (failed.length) {
    console.error('存在失败截图：\n  ' + failed.map(s => `${s.file}: ${s.error}`).join('\n  '))
    process.exit(1)
  }
}

main()
