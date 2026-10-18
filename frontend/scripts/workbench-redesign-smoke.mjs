import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'
import { parse, compileScript } from '@vue/compiler-sfc'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' })
for (const key of ['window', 'document', 'HTMLElement', 'SVGElement', 'Element', 'Node', 'MutationObserver']) globalThis[key] = dom.window[key]
window.matchMedia = () => ({ matches: false })
let frameId = 0
const frames = new Map()
const scheduler = { request: callback => { frames.set(++frameId, callback); return frameId }, cancel: id => frames.delete(id) }
window.requestAnimationFrame = scheduler.request
window.cancelAnimationFrame = scheduler.cancel
const { createApp, effectScope, ref, nextTick } = await import('vue')

async function load(relative) {
  const filename = fileURLToPath(new URL(relative, import.meta.url))
  const result = await build({ entryPoints: [filename], bundle: true, format: 'esm', platform: 'node',
    mainFields: ['module', 'main'], write: false, define: { 'import.meta.env': '{}' },
    plugins: [{ name: 'vue-sfc', setup(builder) {
      builder.onResolve({ filter: /^vue$/ }, () => ({ path: import.meta.resolve('vue'), external: true }))
      builder.onLoad({ filter: /\.vue$/ }, async args => ({ contents: compileScript(parse(await readFile(args.path, 'utf8')).descriptor,
        { id: args.path, inlineTemplate: true }).content, loader: 'ts', resolveDir: dirname(args.path) }))
    } }],
  })
  try { return await import(`data:text/javascript;base64,${Buffer.from(result.outputFiles[0].contents).toString('base64')}`) }
  catch (error) { throw new Error(`Cannot load ${relative}: ${error.message}`) }
}
function tick(time = 32) { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback(time)) }
function drain() { for (let time = 32; frames.size && time < 10000; time += 32) tick(time); assert.equal(frames.size, 0) }
function mount(Component, props) {
  const root = document.createElement('div'); document.body.append(root)
  const app = createApp(Component, props); app.mount(root)
  return { root, close() { app.unmount(); root.remove() } }
}

const { createTextReveal } = await load('../src/utils/textReveal.ts')
let displayed = ''
const reveal = createTextReveal(scheduler, text => { displayed = text })
const burst = '采购比较😀\n'.repeat(300)
reveal.update(burst, true)
assert.equal(displayed, '')

let microDisplay = ''
const microReveal = createTextReveal(scheduler, text => { microDisplay = text })
microReveal.update('采购😀\n', true)
// 恒定 50 字符/秒下，小块需要多帧才走完——这里断言的是「不拆代理对」，
// 而不是「一帧到位」（后者是旧速率 80 字符/秒的副产品）。
for (let frame = 0; frame < 3; frame += 1) tick(100 + frame * 32)
assert.equal(microDisplay, '采购😀\n', 'small chunks render fully without splitting Emoji')
tick(132); assert.equal(microDisplay, '采购😀\n'); microReveal.dispose()
tick(32)
assert.ok(displayed.length > 0 && displayed.length < burst.length, 'large SSE chunk must reveal incrementally')
assert.ok(!/[\uD800-\uDBFF]$/.test(displayed), 'never split a surrogate pair')
reveal.update(burst + '完成', true); drain()
assert.equal(displayed, burst + '完成')
reveal.update(burst + '完成。', true)
reveal.update('后端权威回答', false)
assert.equal(displayed, '后端权威回答'); assert.equal(frames.size, 0)
reveal.update('后端权威回答还有很多字', true); reveal.reset(); tick()
assert.equal(displayed, ''); assert.equal(frames.size, 0)
reveal.update('另一个会话', true); reveal.dispose(); tick()
assert.equal(displayed, '')

const { useAnswerPresentation } = await load('../src/composables/useAnswerPresentation.ts')
const source = ref('历史回答'), streaming = ref(false), identity = ref('turn-1')
const scope = effectScope()
const presentation = scope.run(() => useAnswerPresentation(() => source.value, () => streaming.value, () => identity.value, scheduler))
assert.equal(presentation.displayed.value, '历史回答'); assert.equal(frames.size, 0)
streaming.value = true; source.value += '新的增量'.repeat(80)
assert.notEqual(presentation.displayed.value, source.value)
tick(32); assert.ok(source.value.startsWith(presentation.displayed.value))
streaming.value = false
assert.equal(presentation.displayed.value, source.value); assert.equal(frames.size, 0)
streaming.value = true; source.value += '待清理'; identity.value = 'turn-2'; source.value = '新会话'
assert.equal(presentation.displayed.value, '新会话'); assert.equal(frames.size, 0)
source.value += '新的流'; scope.stop(); assert.equal(frames.size, 0)
window.matchMedia = () => ({ matches: true })
const reducedScope = effectScope()
source.value = ''; identity.value = 'reduce'
const reduced = reducedScope.run(() => useAnswerPresentation(() => source.value, () => true, () => identity.value, scheduler))
source.value = '减少动态效果'; assert.equal(reduced.displayed.value, source.value); assert.equal(frames.size, 0)
reducedScope.stop(); window.matchMedia = () => ({ matches: false })

const { findStableBoundary, StableMarkdownBuffer } = await load('../src/utils/streamingMarkdown.ts')
assert.equal(findStableBoundary('段落\n\n尾部'), 4)
assert.equal(findStableBoundary('```java\na\n\nb'), 0)
assert.equal(findStableBoundary('```java\na\n```\n尾'), 14)
assert.equal(findStableBoundary('~~~~\n```\n\n~~~\n尾'), 0)
assert.equal(findStableBoundary('  ~~~~\r\nx\r\n  ~~~~\r\n尾'), '  ~~~~\r\nx\r\n  ~~~~\r\n'.length)
assert.equal(findStableBoundary('句子\n\n最后一行'), 4)
const stable = new StableMarkdownBuffer()
stable.update('## 结果\n\n|供应商|报价|\n|---|---|\n', true)
const committed = stable.blocks[0]
stable.update('## 结果\n\n|供应商|报价|\n|---|---|\n|A|100|\n', true)
assert.equal(stable.blocks[0], committed)
assert.ok(stable.tail.includes('|A|100|'))
stable.update('修正结果', true); assert.equal(stable.blocks.length, 0)
stable.update('修正结果', false); assert.equal(stable.tail, '')

const { projectTurnConversationItems } = await load('../src/utils/conversationItems.ts')
const { deriveExecutionPhase, deriveComposerControlMode, acceptedProcurementFacts, publicApprovalScope, procurementTools } = await load('../src/utils/procurementPresentation.ts')
const { projectTools } = await load('../src/utils/inspectorProjection.ts')
const now = '2026-10-08T00:00:00Z'
const work = { workItemId: 'work', conversationId: 'conversation', sourceInputId: 'input', originalGoal: '采购工作站',
  activeExecutionTarget: 'PROCUREMENT_SOURCING', activeRunId: 'run', executionState: 'RUNNING', controlState: 'DISPATCHED', createdAt: now, updatedAt: now }
const turn = { ...work, turnId: 'input', userMessage: work.originalGoal, executionTarget: work.activeExecutionTarget }
assert.equal(deriveComposerControlMode(work), 'running')
assert.equal(deriveComposerControlMode({ ...work, executionState: 'WAITING_APPROVAL' }), 'waiting')
for (const executionState of ['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN']) {
  assert.equal(deriveComposerControlMode({ ...work, executionState }), 'idle', 'DISPATCHED must not override terminal authority')
}
assert.equal(deriveComposerControlMode({ ...work, controlState: 'PAUSED' }), 'paused')
assert.equal(deriveComposerControlMode(null, true), 'running')
assert.equal(deriveComposerControlMode(work, false, true), 'cancelling')
const tool = (name, callId, sequence, status = 'ACTIVE', args = {}, run = 'run') => ({ presentationId: `${run}-${sequence}`,
  workItemId: 'work', sequence, sourceType: 'AGENT_RUN', sourceId: run, sourceEventId: `e-${sequence}`, visibility: 'PUBLIC',
  kind: 'TOOL_ACTIVITY', status, title: name, summary: '公开摘要', steps: [], occurredAt: now,
  detail: { referenceId: callId, referenceType: 'TOOL_CALL', attributes: {}, tool: { toolName: name, displayName: name,
    publicArguments: args, actionSummary: '执行目标', resultSummary: status === 'ACTIVE' ? '正在执行' : '已完成', attemptLabel: 'Attempt 1' } } })
function project(presentations, overrides = {}, approval = null) {
  return projectTurnConversationItems({ turn, detail: { workItem: { ...work, ...overrides }, links: [], events: [] },
    inputs: [], presentations, approval, answer: { state: 'IDLE', content: '', persistedMessageId: '', createdAt: '' } })
}
for (const [name, descriptor] of Object.entries(procurementTools)) {
  assert.equal(deriveExecutionPhase(turn, project([tool(name, name, 1)])).label, descriptor.phase)
}
const commercial = tool('procurement_commercial_analysis', 'commercial', 5)
const delivery = tool('procurement_delivery_analysis', 'delivery', 6)
const parallel = project([commercial, delivery])
assert.match(deriveExecutionPhase(turn, parallel).label, /商业条件.*交付/)
for (const executionState of ['COMPLETED', 'CANCELLED', 'FAILED', 'UNKNOWN']) {
  const entries = project([commercial, delivery], { executionState })
  assert.notEqual(deriveExecutionPhase({ ...turn, executionState }, entries).status, 'active')
  assert.ok(entries.filter(item => item.tool).every(item => item.status !== 'active'))
}
const started = tool('procurement_case_patch', 'patch', 1, 'ACTIVE', { productDescription: 'CUDA 工作站', quantity: 2, budget: 10000 })
const done = tool('procurement_case_patch', 'patch', 2, 'COMPLETED')
const entries = project([done, started])
assert.equal(entries.filter(item => item.tool).length, 1)
assert.equal(entries.find(item => item.tool).id, project([started]).find(item => item.tool).id)
assert.equal(entries.find(item => item.tool).tool.arguments.quantity, 2)
assert.equal(projectTools([done, started])[0].item.detail.tool.publicArguments.quantity, 2,
  'inspector preserves public start arguments even when history is out of order')
assert.equal(project([done, { ...started, sourceId: 'other-run' }]).filter(item => item.tool).length, 2)
assert.equal(projectTools([done, { ...started, sourceId: 'other-run' }]).length, 2)
assert.equal(project([{ ...started, detail: { ...started.detail, referenceId: '' } }, { ...done, detail: { ...done.detail, referenceId: '' } }]).filter(item => item.tool).length, 2)
assert.equal(project([{ ...started, visibility: 'INTERNAL' }]).filter(item => item.tool).length, 0)
assert.equal(acceptedProcurementFacts(project([started])).fields.length, 0)
assert.deepEqual(acceptedProcurementFacts(entries).fields.map(field => field.label), ['采购对象', '数量', '预算数值'])
assert.equal(acceptedProcurementFacts(project([tool('procurement_case_patch', 'fail', 2, 'FAILED', { quantity: 10 })])).fields.length, 0)
const recommendation = tool('procurement_recommendation_finalize', 'recommend', 7, 'COMPLETED', { selectedSupplierId: 'supplier-a', evidenceRefs: ['ev1', 42] })
assert.deepEqual(acceptedProcurementFacts(project([recommendation])).evidenceRefs, ['ev1'])
assert.equal(acceptedProcurementFacts(project([recommendation])).supplierId, 'supplier-a')
assert.ok(!('price' in acceptedProcurementFacts(project([recommendation]))))
const approval = { approvalId: 'approval', runId: 'run', reason: '询价操作需要人工确认', status: 'REQUESTED', createdAt: now,
  toolCallRequest: { requestId: 'rfq', toolName: 'procurement_create_rfq', arguments: { supplierId: 'supplier-a', quantity: 2, token: 'secret', idempotencyKey: 'private' } } }
const rfq = tool('procurement_create_rfq', 'rfq', 8)
const waiting = project([rfq], {}, approval)
const approvalPresentation = { ...rfq, kind: 'APPROVAL_REQUIRED', detail: { ...rfq.detail, referenceId: approval.approvalId, tool: undefined } }
assert.equal(project([approvalPresentation, { ...approvalPresentation, presentationId: 'repeat', sequence: 99 }], {}, approval)
  .filter(item => item.type === 'APPROVAL_REQUEST').length, 1, 'repeated approval events produce one action card')
assert.equal(project([approvalPresentation], {}, { ...approval, status: 'EXPIRED' })
  .find(item => item.type === 'APPROVAL_REQUEST').status, 'failed', 'expired approval must not be shown as completed')
assert.equal(waiting.find(item => item.tool).status, 'waiting')
assert.equal(deriveExecutionPhase(turn, waiting).label, '等待人工审批')
assert.equal(project([rfq], {}, { ...approval, runId: 'other-run' }).find(item => item.tool).status, 'active')
assert.equal(project([rfq], {}, { ...approval, toolCallRequest: { ...approval.toolCallRequest, requestId: 'other-call' } }).find(item => item.tool).status, 'active')
assert.deepEqual(publicApprovalScope(approval.toolCallRequest.arguments).map(field => field.value), ['supplier-a', '2'])
assert.equal(deriveExecutionPhase({ ...turn, controlState: 'CLOSED' }, waiting).label, '任务已结束')

const Renderer = (await load('../src/components/ConversationItemRenderer.vue')).default
const approvalItem = waiting.find(item => item.approval)
let decisions = []
const mountedApproval = mount(Renderer, { item: approvalItem, reviewer: 'reviewer', decisionReason: '核对完毕', onDecideApproval: decision => decisions.push(decision) })
assert.match(mountedApproval.root.textContent, /询价操作需要人工确认/)
assert.match(mountedApproval.root.textContent, /supplier-a/)
assert.ok(!mountedApproval.root.textContent.includes('secret'))
const actionButtons = [...mountedApproval.root.querySelectorAll('button')].filter(button => /批准并继续|拒绝/.test(button.textContent))
actionButtons[0].click(); actionButtons[1].click()
assert.deepEqual(decisions, [true, false]); assert.equal(approval.status, 'REQUESTED')
assert.match(mountedApproval.root.textContent, /等待后端审批决定/)
mountedApproval.close()
for (const status of ['APPROVED', 'REJECTED', 'EXPIRED']) {
  const saved = mount(Renderer, { item: { ...approvalItem, approval: { ...approval, status } }, reviewer: '', decisionReason: '' })
  assert.ok(!/批准并继续|>拒绝</.test(saved.root.innerHTML)); saved.close()
}
const rfqCard = mount(Renderer, { item: project([rfq]).find(item => item.tool), reviewer: '', decisionReason: '' })
assert.equal(rfqCard.root.querySelector('.tool-row-details'), null, 'collapsed tools must not mount details')
rfqCard.root.querySelector('.tool-row-toggle').click(); await nextTick()
assert.match(rfqCard.root.textContent, /模拟 RFQ 不代表已向外部供应商发送询价/); rfqCard.close()

// Public names, terminal histories, retry aggregation, and lazy sanitized disclosure.
const { publicToolLabel } = await load('../src/utils/toolPresentation.ts')
assert.equal(publicToolLabel({ toolName: 'mcp.erpnext.procurement_get_item', displayName: '工具调用' }), '查询 ERP 商品信息')
assert.equal(publicToolLabel({ toolName: 'mcp.unknown.lookup', displayName: '工具调用' }), 'mcp.unknown.lookup')
const dynamic = tool('mcp.erpnext.procurement_get_item', 'erp-call', 20)
dynamic.detail.tool.displayName = '工具调用'
assert.equal(deriveExecutionPhase(turn, project([dynamic])).label, '查询 ERP 商品信息')
const retry = tool('procurement_case_patch', 'patch', 3, 'FAILED')
retry.detail.tool.attemptLabel = 'Attempt 2'; retry.detail.tool.resultSummary = '公开失败原因\n下一行详情'
assert.equal(project([started, done, retry, retry]).filter(item => item.tool).length, 1)
assert.equal(project([retry, started, done]).find(item => item.tool).tool.attemptLabel, '第 2 次')
let diagnosed = 0
const retryRow = mount(Renderer, { item: project([retry]).find(item => item.tool), reviewer: '', decisionReason: '', onDiagnostics: () => diagnosed++ })
assert.match(retryRow.root.querySelector('.tool-row-summary').textContent, /公开失败原因/)
retryRow.root.querySelector('.tool-row-toggle').click(); await nextTick()
retryRow.root.querySelector('.tool-row-inspect').click(); assert.equal(diagnosed, 1); retryRow.close()
const secretTool = tool('mcp.unknown.lookup', 'secret-call', 21, 'COMPLETED', { quantity: 2, token: 'never-render-this', nested: { password: 'never-render-that' } })
const secretRow = mount(Renderer, { item: project([secretTool]).find(item => item.tool), reviewer: '', decisionReason: '' })
assert.match(secretRow.root.textContent, /mcp.unknown.lookup/)
assert.equal(secretRow.root.querySelector('pre'), null)
secretRow.root.querySelector('.tool-row-toggle').click(); await nextTick()
assert.ok(!/never-render-this|never-render-that/.test(secretRow.root.textContent))
assert.match(secretRow.root.querySelector('pre').textContent, /quantity/)
assert.equal(secretRow.root.querySelector('.tool-row-json').open, false); secretRow.close()
for (const executionState of ['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN']) {
  const terminalItems = project([rfq, commercial], { executionState }, approval)
  assert.ok(terminalItems.filter(item => item.tool).every(item => item.status === 'unresolved'))
  const historicApproval = terminalItems.find(item => item.approval)
  assert.equal(historicApproval.readOnly, true); assert.equal(approval.status, 'REQUESTED')
  const history = mount(Renderer, { item: historicApproval, reviewer: '', decisionReason: '' })
  assert.ok(!history.root.textContent.includes('等待后端审批决定'))
  assert.ok(!history.root.textContent.includes('批准并继续')); history.close()
  const unresolvedRow = mount(Renderer, { item: terminalItems.find(item => item.tool), reviewer: '', decisionReason: '' })
  assert.equal(unresolvedRow.root.querySelector('.tool-row-summary').textContent, '结果待核对'); unresolvedRow.close()
  assert.ok(terminalItems.filter(item => item.narrative).every(item => item.narrative.items.every(entry => !['active', 'waiting', 'pending'].includes(entry.status))))
}

// Completion is bounded and progressive even when the persisted answer arrives first.
const completeText = '中文 English 😀\n'.repeat(160)
let completeDisplay = ''
const completionReveal = createTextReveal(scheduler, text => { completeDisplay = text })
completionReveal.update(completeText, true); tick(100)
const beforeCompletion = completeDisplay
completionReveal.update(completeText, true, true)
assert.equal(completeDisplay, beforeCompletion, 'completion must not synchronously flush the display')
tick(132); assert.ok(completeDisplay.length > beforeCompletion.length && completeDisplay.length < completeText.length)
for (let time = 164; time <= 804; time += 32) { completionReveal.update(completeText, true, true); tick(time) }
assert.equal(completeDisplay, completeText, 'repeated final updates must not reset the drain deadline')
assert.equal(frames.size, 0); completionReveal.dispose()
const completionSource = ref(''), completionStreaming = ref(true), completionDone = ref(false), completionId = ref('live-complete')
const completionScope = effectScope()
const completion = completionScope.run(() => useAnswerPresentation(() => completionSource.value, () => completionStreaming.value, () => completionId.value, scheduler, () => completionDone.value))
completionSource.value = completeText; tick(100)
completionDone.value = true; completionStreaming.value = false
assert.ok(completion.pendingText.value.length > 0 && completion.revealing.value)
for (let time = 132; time <= 804; time += 32) tick(time)
assert.equal(completion.displayed.value, completeText)
completionId.value = 'saved-history'; completionSource.value = '历史完整回答'
assert.equal(completion.displayed.value, '历史完整回答'); assert.equal(frames.size, 0)
completionScope.stop()
const initialLiveScope = effectScope()
const initialLive = initialLiveScope.run(() => useAnswerPresentation(() => completeText, () => true, () => 'mounted-live', scheduler))
assert.equal(initialLive.displayed.value, '', 'first live chunk must animate even when the answer component mounts after its arrival')
tick(100); assert.ok(initialLive.displayed.value.length > 0 && initialLive.pendingText.value.length > 0)
initialLiveScope.stop(); assert.equal(frames.size, 0)

// Same keyed renderer stays mounted from deltas through final persistence.
const { h: renderNode } = await import('vue')
const answerItem = ref({ id: 'live-answer', type: 'FINAL_ANSWER', title: '回答', content: '', status: 'active', answerState: 'STREAMING', live: true })
const liveRoot = document.createElement('div'); document.body.append(liveRoot)
const liveApp = createApp({ render: () => renderNode(Renderer, { key: answerItem.value.id, item: answerItem.value, reviewer: '', decisionReason: '' }) })
liveApp.mount(liveRoot)
answerItem.value = { ...answerItem.value, content: completeText }; await nextTick(); tick(100); await nextTick()
const liveMarkdown = liveRoot.querySelector('.conversation-markdown')
const earlyLength = liveMarkdown.textContent.length
answerItem.value = { ...answerItem.value, answerState: 'COMPLETED', status: 'completed', live: false }; await nextTick()
assert.equal(liveRoot.querySelector('.conversation-markdown'), liveMarkdown)
assert.equal(liveMarkdown.textContent.length, earlyLength)
assert.match(liveRoot.textContent, /正在显示回答/)
for (let time = 132; time <= 804; time += 32) { tick(time); await nextTick() }
assert.equal(liveMarkdown.dataset.streaming, 'false')
assert.ok(!liveRoot.textContent.includes('正在显示回答'))
for (const endState of ['FAILED', 'CANCELLED']) {
  answerItem.value = { ...answerItem.value, answerState: 'STREAMING', content: '' }; await nextTick()
  answerItem.value = { ...answerItem.value, content: completeText }; await nextTick(); tick(900); await nextTick()
  answerItem.value = { ...answerItem.value, answerState: endState }; await nextTick()
  assert.equal(frames.size, 0, `${endState} must cancel queued display work`)
}
answerItem.value = { ...answerItem.value, answerState: 'STREAMING', content: completeText + '旧会话' }; await nextTick()
answerItem.value = { ...answerItem.value, id: 'new-answer', content: '新会话历史', answerState: 'COMPLETED' }; await nextTick()
assert.equal(frames.size, 0); assert.match(liveRoot.textContent, /新会话历史/)
liveApp.unmount(); liveRoot.remove()

const TurnSection = (await load('../src/components/ConversationTurnSection.vue')).default
const endedSection = mount(TurnSection, { view: { turn: { ...turn, executionState: 'COMPLETED' }, entries: project([done]), stepCount: 2, durationMs: 1200, agentCount: 0 }, index: 0, selected: true, busy: false, reviewer: '', decisionReason: '' })
assert.equal(endedSection.root.querySelectorAll('.tool-call-row').length, 0)
assert.match(endedSection.root.textContent, /1 次工具调用.*2 项活动/)
endedSection.root.querySelector('.turn-execution-toggle').click(); await nextTick()
assert.equal(endedSection.root.querySelectorAll('.tool-call-row').length, 1); endedSection.close()

const StreamingAnswer = (await load('../src/components/StreamingAnswer.vue')).default
const text = ref('## 推荐\n\n```java\npublic class A {}\n'), active = ref(true)
const markdownRoot = document.createElement('div'); document.body.append(markdownRoot)
const { h } = await import('vue')
const answerApp = createApp({ render: () => h(StreamingAnswer, { content: text.value, streaming: active.value }) })
answerApp.mount(markdownRoot)
const headingNode = markdownRoot.querySelector('h2')
assert.equal(headingNode.textContent, '推荐'); assert.equal(markdownRoot.querySelectorAll('pre').length, 0)
text.value += '```\n'; await nextTick()
assert.equal(markdownRoot.querySelector('h2'), headingNode, 'stable block DOM must survive tail updates')
assert.equal(markdownRoot.querySelectorAll('pre').length, 1)
text.value += '\n|供应商|报价|\n|---|---|\n|A|100|\n'; await nextTick()
assert.equal(markdownRoot.querySelectorAll('table').length, 0)
active.value = false; await nextTick(); assert.equal(markdownRoot.querySelectorAll('table').length, 1)
text.value = '<script>alert(1)</script><a href="javascript:evil()">危险链接</a>'; await nextTick()
assert.equal(markdownRoot.querySelectorAll('script').length, 0); assert.equal(markdownRoot.querySelector('a').getAttribute('href'), null)
answerApp.unmount(); markdownRoot.remove()
assert.equal(frames.size, 0)
console.log('workbench redesign passed: event-driven phases, paired tools, authoritative approvals, Unicode reveal, replay-safe display, stable Markdown and DOM interactions')
