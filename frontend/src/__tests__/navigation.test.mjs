import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const bundled = await build({
  entryPoints: [fileURLToPath(new URL('../navigation.ts', import.meta.url))],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
})
const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
const nav = await import(`data:text/javascript;base64,${source}`)

// router.ts 依赖 vue-router 与全部 .vue 视图，esbuild 直接打包成本高；
// 这里只断言「导航目标必须已声明」这一条不变量，读源码正则足够且更稳。
const routerSource = await readFile(fileURLToPath(new URL('../router.ts', import.meta.url)), 'utf8')
const declaredPaths = new Set([...routerSource.matchAll(/path:\s*'([^']+)'/g)].map(m => m[1]))

const allItems = [nav.primaryNav, ...nav.moreNav, nav.settingsNav]

assert.equal(nav.moreNav.length, 6, `「更多」应为 6 项，实际 ${nav.moreNav.length}`)
assert.equal(allItems.length, 8, `导航总数应为 8（工作台 1 + 更多 6 + 设置 1），实际 ${allItems.length}`)

const paths = allItems.map(item => item.to)
assert.equal(new Set(paths).size, paths.length, `导航路径重复：${paths.join(', ')}`)

// 核心不变量：左栏里能点到的每一项都必须有对应路由，否则就是死链。
for (const item of allItems) {
  assert.ok(declaredPaths.has(item.to), `导航目标 ${item.to}（${item.label}）未在 router.ts 中声明`)
}

for (const item of allItems) {
  assert.ok(item.label.trim().length > 0, `${item.to} 缺少 label`)
  assert.ok(item.hint.trim().length > 0, `${item.to} 缺少 hint`)
}

// Run 历史此前在任何导航里都不存在（孤儿页），这里锁住它不会再丢。
assert.ok(nav.moreNav.some(item => item.to === '/runs'), 'Run 历史必须保留在「更多」菜单中')

console.log(`navigation smoke passed (${allItems.length} 项导航均指向已声明路由，无重复；Run 历史入口已锁住)`)
