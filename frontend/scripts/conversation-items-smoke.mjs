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
  messages: [{ messageId: 'message-1', runId: 'run-1', sequence: 1, role: 'ASSISTANT', content: '## Spring 三级缓存', createdAt: work.updatedAt }],
  presentations, tree: null, approval: null, liveAnswer: '',
})

assert.equal(items[0].type, 'USER_MESSAGE')
assert.equal(items.at(-1).type, 'FINAL_ANSWER')
assert.equal(items.filter(item => item.type === 'TOOL_CALL').length, 1)
assert.equal(items.find(item => item.type === 'TOOL_CALL')?.tool?.summary, '工具调用已完成，返回 4 条结果。')
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
  inputs: [], messages: [], presentations: [...presentations, approvalPresentation],
  tree: null, approval, liveAnswer: '',
})
assert.equal(approvalItems.filter(item => item.type === 'APPROVAL_REQUEST').length, 1)
assert.equal(approvalItems.find(item => item.type === 'APPROVAL_REQUEST')?.approval?.approvalId, 'approval-1')

console.log(`conversation projection smoke passed (${items.length} visible items)`)
