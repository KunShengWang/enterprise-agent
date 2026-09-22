import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'
import { build } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'
import { createSSRApp, h } from 'vue'
import { renderToString } from '@vue/server-renderer'

async function load(relative, component = false) {
  const filename = fileURLToPath(new URL(relative, import.meta.url))
  const source = await readFile(filename, 'utf8')
  const contents = component ? compileScript(parse(source).descriptor,
    { id: 'retirement-test', inlineTemplate: true }).content : source
  const result = await build({ stdin: { contents, resolveDir: dirname(filename), loader: 'ts' },
    bundle: true, format: 'esm', platform: 'node', write: false,
    plugins: [{ name: 'shared-vue-and-markdown-stub', setup(builder) {
      builder.onResolve({ filter: /^vue$/ }, () => ({ path: import.meta.resolve('vue'), external: true }))
      builder.onResolve({ filter: /utils\/markdown$/ }, () => ({ path: 'markdown', namespace: 'test' }))
      builder.onLoad({ filter: /.*/, namespace: 'test' }, () => ({ contents: 'export const renderMarkdown = value => value' }))
    } }],
  })
  return import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].contents).toString('base64')}`)
}

const { projectTurnConversationItems } = await load('../src/utils/conversationItems.ts')
const policy = await load('../src/utils/retiredBusiness.ts')
const Renderer = (await load('../src/components/ConversationItemRenderer.vue', true)).default
const now = '2026-09-20T00:00:00Z'
function fixture(target, state, treeType = 'SINGLE_AGENT') {
  const work = { workItemId: 'work', sourceInputId: 'input', activeExecutionTarget: target,
    originalGoal: '已保存的目标', activeRunId: 'run', executionState: state,
    controlState: 'DISPATCHED', createdAt: now, updatedAt: now }
  return { detail: { workItem: work, links: [], events: [], preview: { previewId: 'preview',
    targetId: target, previewVersion: 1, status: 'ACTIVE', payload: {} } },
    turn: { turnId: 'input', workItemId: 'work', createdAt: now, userMessage: work.originalGoal },
    tree: { treeType }, inputs: [], presentations: [],
    approval: { approvalId: 'approval', createdAt: now, status: 'REQUESTED',
      toolCallRequest: { toolName: target === 'PROCUREMENT_SOURCING' ? 'procurement_create_rfq' : 'floworder_recovery_execute' } },
    answer: { state: 'COMPLETED', content: '已物化的历史回答', createdAt: now } }
}
async function render(item) {
  return renderToString(createSSRApp({ render: () => h(Renderer,
    { item, busy: false, reviewer: 'reviewer', decisionReason: 'reviewed' }) }))
}
for (const target of ['ORDERCARE_CASE', 'INCIDENT_INVESTIGATION', 'INCIDENT_RECOVERY_PLAN']) {
  for (const state of ['RUNNING', 'WAITING_APPROVAL', 'PAUSED', 'COMPLETED']) {
    const source = fixture(target, state)
    const before = JSON.stringify(source)
    const items = projectTurnConversationItems(source)
    assert.equal(JSON.stringify(source), before, 'projection must not rewrite persisted state')
    assert.ok(items.every(item => item.readOnly))
    assert.ok(items.some(item => item.content === '已物化的历史回答'))
    assert.ok(items.some(item => item.content.includes('业务已退役、不能继续执行')))
    for (const item of items) {
      const html = await render(item)
      assert.ok(!/确认执行范围|批准并继续|>拒绝<|重试任务|补充信息/.test(html))
    }
  }
}
const retiredTree = fixture('PROCUREMENT_SOURCING', 'RUNNING', 'RETIRED')
assert.ok(projectTurnConversationItems(retiredTree).every(item => item.readOnly))
const scope = fixture('PROCUREMENT_SOURCING', 'RUNNING')
scope.detail.preview.payload = { validatedInput: { scopeSnapshotId: 'old-scope' } }
assert.ok(projectTurnConversationItems(scope).every(item => item.readOnly))
for (const target of ['GENERAL_AGENT', 'PROCUREMENT_SOURCING']) {
  const source = fixture(target, 'WAITING_APPROVAL')
  if (target === 'GENERAL_AGENT') source.approval = null
  const items = projectTurnConversationItems(source)
  assert.ok(items.every(item => !item.readOnly))
  assert.ok((await render(items.find(item => item.type === 'ROUTE_PREVIEW'))).includes('确认执行范围'))
  if (source.approval) assert.ok((await render(items.find(item => item.type === 'APPROVAL_REQUEST'))).includes('批准并继续'))
}
for (const type of ['ERROR', 'AGENT_STATUS']) {
  const html = await render({ id: 'old', type, content: '历史状态', status: 'waiting', readOnly: true,
    presentationKind: 'WAITING_FOR_USER', error: { retryable: true } })
  assert.ok(!/重试任务|补充信息/.test(html))
}
assert.equal(policy.isRetiredRun({ scenarioId: 'ordercare-floworder-v1' }), true)
assert.equal(policy.isRetiredRun({ metadata: { executionTarget: 'INCIDENT_INVESTIGATION' } }), true)
assert.equal(policy.isRetiredRun({ scenarioId: 'general-agent-v1' }), false)
assert.equal(policy.isRetiredTool('mcp.procurement.rfq.create'), false)

const route = await readFile(new URL('../src/router.ts', import.meta.url), 'utf8')
assert.match(route, /path: '\/incident-command'[^\n]*component: RetiredBusinessView/)
assert.ok(!route.includes('IncidentCommandView'))
for (const path of ['AppSidebar.vue', 'WorkbenchTaskSidebar.vue']) {
  assert.ok(!(await readFile(new URL(`../src/components/${path}`, import.meta.url), 'utf8')).includes('/incident-command'))
}
const retiredPage = await readFile(new URL('../src/views/RetiredBusinessView.vue', import.meta.url), 'utf8')
assert.ok(!/api\/|router\.(push|replace)|onMounted|submit|PROCUREMENT_SOURCING/.test(retiredPage))
assert.ok(retiredPage.includes('to="/runs"'))
const trace = await readFile(new URL('../src/types/trace.ts', import.meta.url), 'utf8')
assert.ok(!trace.includes('incident'))
const runtime = await readFile(new URL('../src/views/RuntimeWorkbench.vue', import.meta.url), 'utf8')
assert.ok(runtime.includes("ref('general-agent-v1')"))
assert.ok(!runtime.includes('floworder_recovery'))
assert.ok(runtime.includes('v-if="!retiredHistory" class="composer-area"'))
const approvalCenter = await readFile(new URL('../src/views/ApprovalCenterView.vue', import.meta.url), 'utf8')
assert.ok(approvalCenter.includes('v-if="isRetiredTool(selected.toolCallRequest?.toolName)"'))
assert.ok(approvalCenter.includes('v-else-if="selected.status === \'REQUESTED\'"'))
assert.ok(approvalCenter.includes('!isRetiredTool(item.toolCallRequest?.toolName)'))
console.log('retired history smoke passed: 12 historical states, RETIRED tree, scope, RFQ/General and rendered action boundaries')
