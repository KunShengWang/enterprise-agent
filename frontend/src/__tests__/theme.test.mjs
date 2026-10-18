import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

// 与其它 smoke 一致：用 esbuild 把 .ts 打成 ESM，再经 data: URL 动态导入。
const bundled = await build({
  entryPoints: [fileURLToPath(new URL('../theme.ts', import.meta.url))],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
})
const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
const theme = await import(`data:text/javascript;base64,${source}`)

/**
 * 每个用例装一套全新的 window/document。
 * theme.ts 无模块级状态，所有偏好都在调用时从全局读取，因此换 stub 即可隔离。
 */
function installEnv({ search = '', stored = null } = {}) {
  const store = new Map()
  if (stored !== null) store.set('enterprise-agent-theme', stored)
  globalThis.window = {
    location: { search },
    localStorage: {
      getItem: key => (store.has(key) ? store.get(key) : null),
      setItem: (key, value) => store.set(key, value),
    },
  }
  globalThis.document = { documentElement: { dataset: {} } }
  return { store }
}

// 1. 默认：无 URL、无存储 → 浅色（产品决定，深色为可选项）
installEnv()
assert.equal(theme.resolveTheme(), 'light', '无任何偏好时应回退浅色')

// 2. URL 指定深色
installEnv({ search: '?theme=dark' })
assert.equal(theme.resolveTheme(), 'dark')

// 3. URL 指定浅色
installEnv({ search: '?theme=light' })
assert.equal(theme.resolveTheme(), 'light')

// 4. URL 优先于已存储偏好
installEnv({ search: '?theme=light', stored: 'dark' })
assert.equal(theme.resolveTheme(), 'light', 'URL 应优先于 localStorage')

// 5. 无 URL 时 localStorage 生效
installEnv({ stored: 'dark' })
assert.equal(theme.resolveTheme(), 'dark')

// 6. 非法值被忽略
installEnv({ search: '?theme=purple', stored: 'chartreuse' })
assert.equal(theme.resolveTheme(), 'light', '非法主题值应回退默认')

// 7. applyTheme 写入 data-theme
installEnv()
theme.applyTheme('dark')
assert.equal(globalThis.document.documentElement.dataset.theme, 'dark')

// 8. applyTheme 默认持久化
const persisted = installEnv()
theme.applyTheme('dark')
assert.equal(persisted.store.get('enterprise-agent-theme'), 'dark')

// 9. persist=false 不写存储
const notPersisted = installEnv()
theme.applyTheme('light', false)
assert.equal(notPersisted.store.has('enterprise-agent-theme'), false)

// 10. currentTheme 反映 data-theme
installEnv()
globalThis.document.documentElement.dataset.theme = 'dark'
assert.equal(theme.currentTheme(), 'dark')
globalThis.document.documentElement.dataset.theme = undefined
assert.equal(theme.currentTheme(), 'light', '缺少 data-theme 时应视为浅色')

// 11. toggleTheme 往返
installEnv()
globalThis.document.documentElement.dataset.theme = 'light'
assert.equal(theme.toggleTheme(), 'dark')
assert.equal(theme.currentTheme(), 'dark')
assert.equal(theme.toggleTheme(), 'light')

// 12. bootstrapTheme 应用解析结果但不持久化（首帧前调用，不应写存储）
const bootstrapEnv = installEnv({ search: '?theme=dark' })
assert.equal(theme.bootstrapTheme(), 'dark')
assert.equal(globalThis.document.documentElement.dataset.theme, 'dark')
assert.equal(bootstrapEnv.store.has('enterprise-agent-theme'), false, 'bootstrap 不应持久化')

console.log('theme smoke passed (12 cases: 默认浅色、URL/localStorage 优先级、非法值回退、apply/current/toggle/bootstrap)')
