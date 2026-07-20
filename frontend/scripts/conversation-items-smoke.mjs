import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const bundled = await build({
  entryPoints: [fileURLToPath(new URL('../src/utils/conversationItems.ts', import.meta.url))],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
})
const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
const { projectConversationItems } = await import(`data:text/javascript;base64,${source}`)

const work = {
  workItemId: 'work-1', conversationId: 'conversation-1', sourceInputId: 'input-1',
  originalGoal: '解释 Spring 三级缓存', activeExecutionTarget: 'GENERAL_AGENT', activeRunId: 'run-1',
  activeIncidentId: '', activeRecoveryPlanId: '', controlState: 'DISPATCHED', executionState: 'COMPLETED',
  outcome: 'CLOSED', routingFailureCode: '', version: 1,
  createdAt: '2026-07-20T00:00:00Z', updatedAt: '2026-07-20T00:00:06Z',
}
const presentations = [
  { presentationId: 'p-route', workItemId: 'work-1', sequence: 10, schemaVersion: 1, kind: 'ROUTE_SUMMARY', status: 'COMPLETED', title: '已理解任务', summary: '这是一个知识解释任务。', steps: [], detail: { targetLabel: 'General Agent', referenceType: '', referenceId: '', attributes: {} }, sourceType: 'WORK_ITEM', sourceId: 'work-1', sourceEventId: 'route', occurredAt: '2026-07-20T00:00:01Z', visibility: 'PUBLIC' },
  { presentationId: 'p-process', workItemId: 'work-1', sequence: 11, schemaVersion: 1, kind: 'STANDARD_PROCESS', status: 'COMPLETED', title: '标准流程', summary: '这是产品标准流程。', steps: ['检索资料', '整理回答'], detail: { targetLabel: 'General Agent', referenceType: '', referenceId: '', attributes: {} }, sourceType: 'WORK_ITEM', sourceId: 'work-1', sourceEventId: 'route', occurredAt: '2026-07-20T00:00:01Z', visibility: 'PUBLIC' },
  { presentationId: 'p-tool', workItemId: 'work-1', sequence: 20, schemaVersion: 1, kind: 'TOOL_ACTIVITY', status: 'COMPLETED', title: '知识检索', summary: '工具调用已完成，返回 4 条结果。', steps: [], detail: { targetLabel: '', referenceType: 'TOOL_CALL', referenceId: 'call-1', attributes: {}, tool: { toolName: 'knowledge_search', displayName: '知识检索', actionSummary: '正在检索相关知识', publicArguments: { query: 'Spring 三级缓存' }, resultSummary: '工具调用已完成，返回 4 条结果。', resultCount: 4, durationMs: 386, attemptLabel: 'Attempt 1' } }, sourceType: 'AGENT_RUN', sourceId: 'run-1', sourceEventId: 'tool', occurredAt: '2026-07-20T00:00:05Z', visibility: 'PUBLIC' },
]
const items = projectConversationItems({
  detail: { workItem: work, routingDecision: { decisionId: 'decision-1', decision: { userFacingSummary: '这是一个知识解释任务。' }, validation: {}, failureCode: '', failureReason: '' }, links: [], events: [] },
  inputs: [{ inputId: 'input-1', clientInputId: 'client-1', conversationId: 'conversation-1', content: work.originalGoal, inputKind: 'NORMAL_GOAL', classificationStatus: 'CLASSIFIED', createdAt: work.createdAt }],
  presentations, approval: null,
  answer: { state: 'COMPLETED', content: '## Spring 三级缓存', persistedMessageId: 'message-1', createdAt: work.updatedAt },
})

assert.equal(items[0].type, 'USER_MESSAGE')
assert.equal(items.at(-1).type, 'FINAL_ANSWER')
assert.equal(items.filter(item => item.type === 'TOOL_CALL').length, 1)
assert.equal(items.find(item => item.type === 'TOOL_CALL')?.tool?.summary, '工具调用已完成，返回 4 条结果。')
assert.equal(items.find(item => item.type === 'TOOL_CALL')?.tool?.actionSummary, '正在检索相关知识')
assert.equal(items.find(item => item.type === 'TOOL_CALL')?.tool?.resultCount, 4)
assert.equal(items.find(item => item.type === 'TOOL_CALL')?.tool?.attemptLabel, 'Attempt 1')
assert.deepEqual(items.find(item => item.type === 'TOOL_CALL')?.tool?.arguments, { query: 'Spring 三级缓存' })
assert.ok(items.some(item => item.type === 'ROUTE_SUMMARY'))
assert.ok(items.some(item => item.type === 'TASK_PLAN'))
assert.ok(!items.some(item => item.content.includes('internal reason') || item.content.includes('modelConfidence')))
assert.equal(items.find(item => item.type === 'TASK_PLAN')?.title, '标准流程')

const approval = {
  approvalId: 'approval-1', runId: 'run-1', status: 'REQUESTED', createdAt: work.updatedAt,
}
const approvalPresentation = {
  ...presentations[0], presentationId: 'p-approval', sequence: 30,
  kind: 'APPROVAL_REQUIRED', status: 'WAITING', title: 'Approval required', summary: 'Manual confirmation required.',
}
const approvalItems = projectConversationItems({
  detail: { workItem: work, routingDecision: null, links: [], events: [] },
  inputs: [], presentations: [...presentations, approvalPresentation], approval,
  answer: { state: 'IDLE', content: '', persistedMessageId: '', createdAt: '' },
})
assert.equal(approvalItems.filter(item => item.type === 'APPROVAL_REQUEST').length, 1)
assert.equal(approvalItems.find(item => item.type === 'APPROVAL_REQUEST')?.approval?.approvalId, 'approval-1')

const requestTool = { ...presentations[2], presentationId: 'p-tool-request', sequence: 19, status: 'ACTIVE' }
const duplicatePlan = { ...presentations[1], presentationId: 'p-plan', sequence: 12, kind: 'EXECUTION_PLAN', title: '执行计划' }
const internal = { ...presentations[0], presentationId: 'p-internal', sequence: 13, visibility: 'INTERNAL', summary: 'hidden reasoning' }
const inspector = { ...presentations[0], presentationId: 'p-inspector', sequence: 14, visibility: 'INSPECTOR_ONLY', summary: 'technical only' }
const finalResult = { ...presentations[0], presentationId: 'p-final', sequence: 30, kind: 'FINAL_RESULT', title: '结果已生成', summary: '最终正文以 Primary Run 为准。' }
const deduplicated = projectConversationItems({
  detail: { workItem: work, routingDecision: null, links: [], events: [] }, inputs: [],
  presentations: [...presentations, requestTool, duplicatePlan, internal, inspector, finalResult], approval: null,
  answer: { state: 'STREAMING', content: 'live answer', persistedMessageId: '', createdAt: work.updatedAt },
})
assert.equal(deduplicated.filter(item => item.type === 'TOOL_CALL').length, 1)
assert.equal(deduplicated.filter(item => item.type === 'TASK_PLAN').length, 1)
assert.equal(deduplicated.filter(item => item.type === 'FINAL_ANSWER').length, 1)
assert.ok(!deduplicated.some(item => item.content.includes('hidden reasoning') || item.content.includes('technical only')))
assert.ok(deduplicated.some(item => item.content.includes('Primary Run')))

const failed = projectConversationItems({
  detail: { workItem: { ...work, executionState: 'FAILED' }, routingDecision: null, links: [], events: [] },
  inputs: [], presentations: [], approval: null,
  answer: { state: 'FAILED', content: '', persistedMessageId: '', createdAt: work.updatedAt },
})
assert.equal(failed.at(-1)?.type, 'FINAL_ANSWER')
assert.equal(failed.at(-1)?.answerState, 'FAILED')

console.log(`conversation projection smoke passed (${items.length} visible items)`)
