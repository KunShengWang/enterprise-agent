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

const attachmentItems = projectConversationItems({
  detail: { workItem: work, routingDecision: null, links: [], events: [] },
  inputs: [{
    inputId: 'input-1', clientInputId: 'client-attachment', conversationId: 'conversation-1',
    content: '解释这段代码\n\n<workbench_attachments>\n<attachment name="Demo.java" media-type="text/plain" size="18">\nclass Demo {}\n</attachment>\n</workbench_attachments>',
    inputKind: 'NORMAL_GOAL', classificationStatus: 'CLASSIFIED', createdAt: work.createdAt,
  }],
  presentations: [], approval: null,
  answer: { state: 'IDLE', content: '', persistedMessageId: '', createdAt: '' },
})
assert.equal(attachmentItems[0].content, '解释这段代码')
assert.deepEqual(attachmentItems[0].attachments, [{ name: 'Demo.java', mediaType: 'text/plain', size: 18 }])
assert.ok(!attachmentItems[0].content.includes('class Demo'))

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

const routeConfirmation = {
  ...presentations[0], presentationId: 'p-confirmation', sequence: 31,
  kind: 'CONFIRMATION_REQUIRED', status: 'WAITING', title: '需要确认调查范围',
  summary: '请确认预览中的目标和只读边界后再启动调查。',
}
const incidentWork = { ...work, activeExecutionTarget: 'INCIDENT_INVESTIGATION', activeRunId: '', executionState: 'NOT_STARTED' }
const confirmationItems = projectConversationItems({
  detail: { workItem: incidentWork, routingDecision: null, links: [], events: [], preview: {
    previewId: 'preview-1', previewVersion: 1, targetId: 'INCIDENT_INVESTIGATION',
    validatedInputDigest: 'input-digest', scopeDigest: 'scope-digest', status: 'ACTIVE',
    expiresAt: '2026-07-21T18:00:00Z', payload: { validatedInput: {
      requestIds: ['IC-HAPPY-REQ-001', 'IC-HAPPY-REQ-002'], queueNames: ['floworder.order.state.dlq'],
    } },
  } },
  inputs: [], presentations: [...presentations, routeConfirmation], approval: null,
  answer: { state: 'IDLE', content: '', persistedMessageId: '', createdAt: '' },
})
assert.equal(confirmationItems.filter(item => item.type === 'APPROVAL_REQUEST').length, 0)
assert.equal(confirmationItems.filter(item => item.type === 'INCIDENT_PREVIEW').length, 1)
assert.equal(confirmationItems.find(item => item.type === 'INCIDENT_PREVIEW')?.title, '启动只读 Multi-Agent 事故调查')

const discoveredScopeItems = projectConversationItems({
  detail: { workItem: incidentWork, routingDecision: null, links: [], events: [], preview: {
    previewId: 'preview-scope', previewVersion: 1, targetId: 'INCIDENT_INVESTIGATION',
    validatedInputDigest: 'input-digest', scopeDigest: 'scope-digest', status: 'ACTIVE',
    expiresAt: '2026-07-21T18:00:00Z', payload: { validatedInput: {
      scopeSnapshotId: 'scope-1', scopeSnapshotVersion: 3, candidateFingerprint: 'fingerprint-1',
      candidateCount: 1, timeStart: '2026-07-20T10:00:00Z', timeEnd: '2026-07-20T22:00:00Z',
      timezone: 'Asia/Shanghai', anomalyTypes: ['ORDER_TIMEOUT_INVENTORY_UNRELEASED'],
      requestIds: ['REQ-1'], queueNames: [], sourceHealth: { order: 'AVAILABLE', resource: 'AVAILABLE' },
      scopeCandidates: [{ requestId: 'REQ-1', orderNo: 'ORDER-1', deductNo: 'DEDUCT-1',
        deadLetterIds: [], queueNames: [], inclusionReasons: ['inventory unreleased'],
        relationQuality: 'MISSING', completeness: 'COMPLETE' }],
    } },
  } },
  inputs: [], presentations: [...presentations, routeConfirmation], approval: null,
  answer: { state: 'IDLE', content: '', persistedMessageId: '', createdAt: '' },
})
const discoveredPreview = discoveredScopeItems.find(item => item.type === 'INCIDENT_PREVIEW')
assert.equal(discoveredPreview?.title, '确认候选事故范围')
assert.ok(discoveredPreview?.content.includes('FlowOrder 权威只读事实'))
assert.ok(discoveredPreview?.content.includes('订单和库存 Specialist'))

const phantomApprovalItems = projectConversationItems({
  detail: { workItem: incidentWork, routingDecision: null, links: [], events: [] }, inputs: [],
  presentations: [...presentations, approvalPresentation], approval: null,
  answer: { state: 'IDLE', content: '', persistedMessageId: '', createdAt: '' },
})
assert.equal(phantomApprovalItems.filter(item => item.type === 'APPROVAL_REQUEST').length, 0)

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
  inputs: [], presentations: [{ ...presentations[0], presentationId: 'p-error', sequence: 40,
    kind: 'ERROR', status: 'FAILED', title: '模型调用失败',
    summary: '系统未能获得模型响应，本次任务没有形成最终答案。',
    detail: { targetLabel: '', referenceType: 'PRIMARY_RUN', referenceId: 'run-1',
      attributes: { safeErrorCode: 'MODEL_PROTOCOL_ERROR', retryable: 'true', correlationId: 'corr-1' } } }], approval: null,
  answer: { state: 'FAILED', content: '', persistedMessageId: '', createdAt: work.updatedAt },
})
assert.equal(failed.filter(item => item.type === 'FINAL_ANSWER').length, 0)
assert.equal(failed.at(-1)?.type, 'ERROR')
assert.equal(failed.at(-1)?.error?.code, 'MODEL_PROTOCOL_ERROR')
assert.equal(failed.at(-1)?.error?.retryable, true)

const waitingForInput = projectConversationItems({
  detail: { workItem: { ...work, activeRunId: '', controlState: 'WAITING_INPUT', executionState: 'NOT_STARTED', outcome: 'UNDETERMINED' }, routingDecision: null, links: [], events: [] },
  inputs: [], presentations: [{ ...presentations[0], presentationId: 'p-clarification', sequence: 40,
    kind: 'WAITING_FOR_USER', status: 'WAITING', title: '需要补充信息',
    summary: '请在下方输入框补充以下信息，提交后系统会继续当前任务。',
    steps: ['消息队列名称（queueNames）', '事故范围：提供 batchId，或一个或多个 requestId'] }],
  approval: null, answer: { state: 'IDLE', content: '', persistedMessageId: '', createdAt: '' },
})
assert.equal(waitingForInput.filter(item => item.type === 'FINAL_ANSWER').length, 0)
assert.equal(waitingForInput.at(-1)?.presentationKind, 'WAITING_FOR_USER')
assert.deepEqual(waitingForInput.at(-1)?.steps, ['消息队列名称（queueNames）', '事故范围：提供 batchId，或一个或多个 requestId'])

const previousWork = {
  ...work, workItemId: 'work-previous', sourceInputId: 'input-previous', activeRunId: 'run-previous',
  originalGoal: '只用 JSON 解释三级缓存', createdAt: '2026-07-19T23:58:00Z', updatedAt: '2026-07-19T23:59:00Z',
}
const conversationHistory = projectConversationItems({
  detail: { workItem: work, routingDecision: null, links: [], events: [] },
  inputs: [
    { inputId: 'input-previous', clientInputId: 'client-previous', conversationId: 'conversation-1', content: previousWork.originalGoal, inputKind: 'NORMAL_GOAL', classificationStatus: 'CLASSIFIED', createdAt: previousWork.createdAt },
    { inputId: 'input-1', clientInputId: 'client-1', conversationId: 'conversation-1', content: work.originalGoal, inputKind: 'NORMAL_GOAL', classificationStatus: 'CLASSIFIED', createdAt: work.createdAt },
  ],
  workItems: [previousWork, work],
  messages: [{ messageId: 'message-previous', runId: 'run-previous', sequence: 2, role: 'ASSISTANT', content: '{"assistantText":"上一轮正文"}', createdAt: previousWork.updatedAt }],
  presentations: [], approval: null,
  answer: { state: 'COMPLETED', content: '当前轮正文', persistedMessageId: 'message-current', createdAt: work.updatedAt },
})
assert.deepEqual(conversationHistory.map(item => item.type), ['USER_MESSAGE', 'FINAL_ANSWER', 'USER_MESSAGE', 'FINAL_ANSWER'])
assert.equal(conversationHistory[1].content, '上一轮正文')
assert.ok(!conversationHistory[1].content.includes('assistantText'))

console.log(`conversation projection smoke passed (${items.length} visible items)`)
