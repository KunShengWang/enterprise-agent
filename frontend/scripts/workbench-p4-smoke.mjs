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
assert.equal(
  normalizeMarkdown('##4. 完整示例：模拟循环依赖```javapublic class Demo {\n}\n```'),
  '## 4. 完整示例：模拟循环依赖\n```java\npublic class Demo {\n}\n```',
)
assert.equal(
  normalizeMarkdown('##3. AOP 代理的关系**为什么二级缓存不够？**'),
  '## 3. AOP 代理的关系\n\n**为什么二级缓存不够？**',
)
assert.equal(
  normalizeMarkdown('- **循环依赖**：已解决- **AOP 代理**：保持一致'),
  '- **循环依赖**：已解决\n- **AOP 代理**：保持一致',
)

const mergedJava = `\`\`\`java
  // 一级缓存：完整单例 private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();
  Object bean = factory.getObject(); // 创建早期引用
\`\`\``
assert.equal(
  normalizeMarkdown(mergedJava),
  `\`\`\`java
  // 一级缓存：完整单例
  private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>();
  Object bean = factory.getObject();
  // 创建早期引用
\`\`\``,
)
assert.equal(
  normalizeMarkdown('```java\n  private final String value = "unchanged";\n```'),
  '```java\n  private final String value = "unchanged";\n```',
)
assert.equal(
  normalizeMarkdown('普通文本 // 说明 private final String value = "unchanged";'),
  '普通文本 // 说明 private final String value = "unchanged";',
)
const flatJava = `\`\`\`java
protected Object getSingleton(String beanName) {
//1. 查一级缓存
Object singletonObject = this.singletonObjects.get(beanName);
if (singletonObject == null) {
//2. 查二级缓存 singletonObject = this.earlySingletonObjects.get(beanName);
if (singletonObject == null) {
// 移到二级缓存 this.earlySingletonObjects.put(beanName, singletonObject);
}
}
return singletonObject;
}
\`\`\``
assert.equal(
  normalizeMarkdown(flatJava),
  `\`\`\`java
protected Object getSingleton(String beanName) {
    // 1. 查一级缓存
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject == null) {
        // 2. 查二级缓存
        singletonObject = this.earlySingletonObjects.get(beanName);
        if (singletonObject == null) {
            // 移到二级缓存
            this.earlySingletonObjects.put(beanName, singletonObject);
        }
    }
    return singletonObject;
}
\`\`\``,
)
const alreadyIndentedJava = '```java\nclass Demo {\n    void run() {\n        work();\n    }\n}\n```'
assert.equal(normalizeMarkdown(alreadyIndentedJava), alreadyIndentedJava)

const attachedTable = `###三级缓存的结构|缓存级别|名称|数据结构|用途|
|---|---|---|---|
|一级缓存|singletonObjects|Map<String, Object>|完整单例|`
const normalizedTable = normalizeMarkdown(attachedTable)
assert.equal(
  normalizedTable,
  `### 三级缓存的结构

|缓存级别|名称|数据结构|用途|
|---|---|---|---|
|一级缓存|singletonObjects|Map<String, Object>|完整单例|`,
)

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
const renderedAttachedTable = renderMarkdown(attachedTable)
assert.match(renderedAttachedTable, /<h3>三级缓存的结构<\/h3>/)
assert.match(renderedAttachedTable, /<table>/)
assert.match(renderedAttachedTable, /<th>缓存级别<\/th>/)
const repairedCode = renderMarkdown('##4. 示例```javapublic class Demo {}\n```')
assert.match(repairedCode, /<h2>4\. 示例<\/h2>/)
assert.match(repairedCode, /<code class="hljs language-java">/)
assert.match(repairedCode, /hljs-keyword/)
assert.match(repairedCode, /hljs-title class_/)
assert.match(repairedCode, /<span class="hljs-keyword">public<\/span>/)
assert.ok(!repairedCode.includes('&lt;span class="hljs-'))
assert.equal(renderMarkdown('```java\npublic class Demo {}\n```'), renderMarkdown('```java\npublic class Demo {}\n```'))
const unknownCode = renderMarkdown('```unknown\n<script>alert(1)</script>\n```')
assert.ok(!unknownCode.includes('<script>'))
assert.match(unknownCode, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/)
const malicious = renderMarkdown('<img src=x onerror="alert(1)"><script>alert(2)</script><a href="javascript:alert(3)">x</a>')
assert.ok(!malicious.includes('<img'))
assert.ok(!malicious.includes('<script'))
assert.ok(!malicious.includes('javascript:'))
assert.ok(!malicious.includes('onerror'))

const { isToolCallProtocolEnvelope, normalizeAssistantContent } = await loadModule('../src/utils/publicContent.ts')
assert.equal(isToolCallProtocolEnvelope('{"assistantText":"","toolCalls":[{"name":"search"}]}'), true)
assert.equal(isToolCallProtocolEnvelope('{"assistantText":"","toolCalls":['), true)
assert.equal(isToolCallProtocolEnvelope('{"status":"ok","toolCalls":"documentation"}'), false)
assert.equal(isToolCallProtocolEnvelope('The toolCalls field is documentation.'), false)
assert.equal(normalizeAssistantContent('{"assistantText":"## Java concurrency"}'), '## Java concurrency')
assert.equal(normalizeAssistantContent('{"assistantText":"answer","toolCalls":[]}'), 'answer')
assert.equal(normalizeAssistantContent('{"assistantText":"business","status":"ok"}'), '{"assistantText":"business","status":"ok"}')

console.log('workbench P4 markdown and public-content smoke passed')
