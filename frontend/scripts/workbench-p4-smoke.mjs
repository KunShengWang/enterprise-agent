import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import { JSDOM } from 'jsdom'

async function loadModule(relativePath) {
  const bundled = await build({
    entryPoints: [fileURLToPath(new URL(relativePath, import.meta.url))],
    bundle: true,
    format: 'esm',
    platform: 'node',
    write: false,
  })
  const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
  return import(`data:text/javascript;base64,${source}`)
}

const { normalizeMarkdown } = await loadModule('../src/utils/markdownNormalizer.ts')
assert.equal(normalizeMarkdown('###三级缓存如何解决循环依赖？'), '### 三级缓存如何解决循环依赖？')
assert.equal(normalizeMarkdown('#一级\n##二级\n###三级'), '# 一级\n## 二级\n### 三级')
assert.equal(normalizeMarkdown('-第一项\n-第二项'), '- 第一项\n- 第二项')
assert.ok(normalizeMarkdown('说明\n| A | B |\n|---|---|\n|1|2|').includes('说明\n\n| A | B |'))
const code = '```java\n###不是标题\n-list\n```'
assert.equal(normalizeMarkdown(code), code)
const jsonBlock = '```json\n{"heading":"###标题","url":"https://example.com/a-b"}\n```'
assert.equal(normalizeMarkdown(jsonBlock), jsonBlock)
const businessJson = '{"status":"ok","label":"###标题","url":"https://example.com/a-b"}'
assert.equal(normalizeMarkdown(businessJson), businessJson)
assert.equal(normalizeMarkdown(''), '')
assert.equal(normalizeMarkdown(`###标题\n${'正文'.repeat(20_000)}`).length > 40_000, true)

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost' })
globalThis.window = dom.window
globalThis.document = dom.window.document
globalThis.Node = dom.window.Node
const { renderMarkdown } = await loadModule('../src/utils/markdown.ts')
const rendered = renderMarkdown('###三级缓存\n\n-列表\n\n|A|B|\n|-|-|\n|1|2|\n\n`code`\n\n> quote')
assert.match(rendered, /<h3>三级缓存<\/h3>/)
assert.match(rendered, /<ul>/)
assert.match(rendered, /<table>/)
assert.match(rendered, /<blockquote>/)
const malicious = renderMarkdown('<img src=x onerror="alert(1)"><script>alert(2)</script><a href="javascript:alert(3)">x</a>')
assert.ok(!malicious.includes('<img'))
assert.ok(!malicious.includes('<script'))
assert.ok(!malicious.includes('javascript:'))
assert.ok(!malicious.includes('onerror'))

const { isToolCallProtocolEnvelope } = await loadModule('../src/utils/publicContent.ts')
assert.equal(isToolCallProtocolEnvelope('{"assistantText":"","toolCalls":[{"name":"search"}]}'), true)
assert.equal(isToolCallProtocolEnvelope('{"assistantText":"","toolCalls":['), true)
assert.equal(isToolCallProtocolEnvelope('{"status":"ok","toolCalls":"documentation"}'), false)
assert.equal(isToolCallProtocolEnvelope('The toolCalls field is documentation.'), false)

console.log('workbench P4 markdown and public-content smoke passed')
