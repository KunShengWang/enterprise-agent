import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

globalThis.window = { setTimeout, clearTimeout }

async function loadModule(relativePath) {
  const bundled = await build({
    entryPoints: [fileURLToPath(new URL(relativePath, import.meta.url))],
    bundle: true,
    format: 'esm',
    platform: 'node',
    write: false,
    define: { 'import.meta.env': '{}' },
  })
  const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
  return import(`data:text/javascript;base64,${source}`)
}

class FakeEventSource {
  listeners = new Map()
  onopen = null
  onerror = null
  closed = false
  constructor(url) { this.url = url }
  addEventListener(type, listener) {
    const values = this.listeners.get(type) ?? []
    values.push(listener)
    this.listeners.set(type, values)
  }
  emit(type, data, lastEventId = '') {
    const event = { data: JSON.stringify(data), lastEventId }
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }
  close() { this.closed = true }
}

const flush = () => new Promise(resolve => setTimeout(resolve, 5))
const now = '2026-07-21T00:00:00Z'
const presentation = (id, sequence, visibility = 'PUBLIC', workItemId = 'work-1') => ({
  presentationId: id, workItemId, sequence, schemaVersion: 1, kind: 'ACTION_STARTED',
  status: 'ACTIVE', title: id, summary: id, steps: [],
  detail: { targetLabel: '', referenceType: '', referenceId: '', attributes: {} },
  sourceType: 'WORK_ITEM', sourceId: workItemId, sourceEventId: `event-${id}`,
  occurredAt: now, visibility,
})

const { usePresentationStream } = await loadModule('../src/composables/usePresentationStream.ts')
const presentationSources = []
const histories = {
  'work-1': [presentation('p-1', 10), presentation('p-internal', 11, 'INTERNAL')],
  'work-2': [presentation('p-2', 20, 'PUBLIC', 'work-2')],
}
const inspectorHistories = {
  'work-1': [presentation('p-1', 10), presentation('p-tech', 12, 'INSPECTOR_ONLY')],
  'work-2': [presentation('p-2', 20, 'PUBLIC', 'work-2')],
}
const presentationStream = usePresentationStream({
  history: async workItemId => histories[workItemId] ?? [],
  inspectorHistory: async workItemId => inspectorHistories[workItemId] ?? [],
  streamUrl: (workItemId, cursor) => `presentation://${workItemId}?after=${cursor}`,
  eventSourceFactory: url => { const source = new FakeEventSource(url); presentationSources.push(source); return source },
  reconnectDelayMs: 0,
})

await presentationStream.start('work-1')
assert.equal(presentationStream.publicPresentations.value.length, 1)
assert.equal(presentationStream.inspectorPresentations.value.length, 2)
assert.ok(!presentationStream.inspectorPresentations.value.some(item => item.visibility === 'INTERNAL'))
const firstPresentationSource = presentationSources.at(-1)
assert.ok(firstPresentationSource.url.endsWith('after=10'))
firstPresentationSource.emit('presentation', presentation('p-1', 10))
firstPresentationSource.emit('presentation', presentation('p-live', 13))
assert.equal(presentationStream.publicPresentations.value.length, 2)
firstPresentationSource.onerror?.({})
await flush()
assert.ok(presentationSources.length >= 2)
assert.equal(presentationStream.publicPresentations.value.filter(item => item.presentationId === 'p-1').length, 1)

await presentationStream.start('work-2')
assert.equal(firstPresentationSource.closed, true)
firstPresentationSource.emit('presentation', presentation('late-work-1', 99))
assert.deepEqual(presentationStream.publicPresentations.value.map(item => item.workItemId), ['work-2'])

const { useWorkbenchConversation } = await loadModule('../src/composables/useWorkbenchConversation.ts')
const work = {
  workItemId: 'work-1', conversationId: 'conversation-1', sourceInputId: 'input-1', originalGoal: 'goal',
  activeExecutionTarget: 'GENERAL_AGENT', activeRunId: 'run-1', activeIncidentId: '', activeRecoveryPlanId: '',
  controlState: 'DISPATCHED', executionState: 'RUNNING', outcome: 'UNDETERMINED', routingFailureCode: '',
  version: 1, createdAt: now, updatedAt: now,
}
const detail = { value: { workItem: work, links: [], events: [] } }
const inputs = { value: [{ inputId: 'input-1', content: 'goal', createdAt: now }] }
const publicPresentations = { value: [presentation('final-result', 30)] }
publicPresentations.value[0].kind = 'FINAL_RESULT'
const approval = { value: null }
const conversation = useWorkbenchConversation({ detail, inputs, presentations: publicPresentations, approval })
conversation.prepareWork('work-1', 'run-1', true)
assert.equal(conversation.answerState.value, 'WAITING')

const { usePrimaryRunStream } = await loadModule('../src/composables/usePrimaryRunStream.ts')
const primarySources = []
const primaryStream = usePrimaryRunStream({
  expectedRunId: () => conversation.primaryRunId.value,
  onDelta: event => conversation.applyDelta(event),
  onTerminal: state => conversation.markTerminal(state),
  onReplayStart: () => conversation.restartLiveReplay(),
  history: async () => [],
  streamUrl: (workItemId, workCursor, runCursor) => `primary://${workItemId}?w=${workCursor}&r=${runCursor}`,
  eventSourceFactory: url => { const source = new FakeEventSource(url); primarySources.push(source); return source },
  reconnectDelayMs: 0,
})
primaryStream.start(detail.value)
const firstPrimarySource = primarySources.at(-1)
const delta = { kind: 'MODEL_DELTA', eventId: 'delta-1', workSequence: -1, sourceType: 'AGENT_RUN',
  sourceId: 'run-1', sourceSequence: 4, eventType: 'MODEL_DELTA', content: 'Hello', payload: {},
  createdAt: now, resumeToken: 'w:-1;r:4' }
firstPrimarySource.emit('model-delta', delta)
firstPrimarySource.emit('model-delta', delta)
firstPrimarySource.emit('model-delta', { ...delta, eventId: 'child-delta', sourceId: 'child-run', content: 'child' })
assert.equal(conversation.liveAnswerBuffer.value, 'Hello')
assert.equal(conversation.answerState.value, 'STREAMING')
assert.equal(primaryStream.runCursor.value, 4)
firstPrimarySource.emit('work-event', { ...delta, kind: 'WORK_EVENT', eventId: 'child-terminal',
  workSequence: 0, sourceId: 'child-run', sourceSequence: 5, eventType: 'RUN_EVENT_PROJECTED',
  content: 'child completed', payload: { runtimeEventType: 'RUN_COMPLETED' }, resumeToken: 'w:0;r:5' })
assert.equal(conversation.answerState.value, 'STREAMING')

firstPrimarySource.emit('gap', { ...delta, eventId: 'gap-1' })
await flush()
assert.equal(conversation.answerState.value, 'WAITING')
assert.equal(conversation.liveAnswerBuffer.value, '')
const replayPrimarySource = primarySources.at(-1)
replayPrimarySource.emit('model-delta', delta)
assert.equal(conversation.liveAnswerBuffer.value, 'Hello')

replayPrimarySource.emit('work-event', { ...delta, kind: 'WORK_EVENT', eventId: 'terminal-1', workSequence: 0,
  sourceSequence: 5, eventType: 'RUN_EVENT_PROJECTED', content: 'completed',
  payload: { runtimeEventType: 'RUN_COMPLETED' }, resumeToken: 'w:0;r:5' })
assert.equal(conversation.answerState.value, 'FINALIZING')
conversation.applyPersisted([{ messageId: 'message-1', runId: 'run-1', sequence: 1, role: 'ASSISTANT',
  content: 'Authoritative answer', createdAt: now }], 'run-1')
assert.equal(conversation.answerState.value, 'COMPLETED')
assert.equal(conversation.liveAnswerBuffer.value, 'Authoritative answer')
assert.equal(conversation.entries.value.filter(item => item.type === 'FINAL_ANSWER').length, 1)
conversation.prepareWork('failed-work', 'failed-run', true)
conversation.markTerminal('FAILED')
assert.equal(conversation.answerState.value, 'FAILED')
conversation.prepareWork('cancelled-work', 'cancelled-run', true)
conversation.markTerminal('CANCELLED')
assert.equal(conversation.answerState.value, 'CANCELLED')

const { incidentAssessmentMarkdown } = await loadModule('../src/utils/incidentAssessment.ts')
const incidentTree = {
  workItemId: 'incident-work', executionTarget: 'INCIDENT_INVESTIGATION', treeType: 'INCIDENT',
  executionId: 'incident-1', agents: [], evidence: [{ evidenceId: 'evidence-1' }], conflicts: [],
  assessment: { outcome: 'ASSESSED', riskLevel: 'LOW', incidentId: 'incident-1',
    confirmedFacts: [{ statement: '三笔订单均已进入超时终态。' }], rootCauseCandidates: [],
    recommendations: [], evidenceGaps: [], conflicts: [] }, recoveryPlans: [], metrics: {},
}
const assessmentAnswer = incidentAssessmentMarkdown(incidentTree)
assert.ok(assessmentAnswer.includes('# 事故调查 Assessment'))
assert.ok(assessmentAnswer.includes('三笔订单均已进入超时终态。'))
assert.ok(assessmentAnswer.includes('未执行恢复'))
detail.value = { ...detail.value, workItem: { ...work, workItemId: 'incident-work', activeRunId: '',
  activeExecutionTarget: 'INCIDENT_INVESTIGATION', controlState: 'CLOSED', executionState: 'COMPLETED', outcome: 'ASSESSED' } }
conversation.prepareWork('incident-work', '', false)
assert.equal(conversation.applyProjectedResult(assessmentAnswer, now), true)
conversation.markTerminal('COMPLETED')
assert.equal(conversation.answerState.value, 'COMPLETED')
assert.equal(conversation.entries.value.filter(item => item.type === 'FINAL_ANSWER').length, 1)
assert.equal(conversation.applyDelta({ ...delta, sourceId: 'late-run', content: 'late' }), false)
assert.ok(!conversation.liveAnswerBuffer.value.includes('late'))

primaryStream.start({ ...detail.value, workItem: { ...work, workItemId: 'work-2', activeRunId: 'run-2' } })
assert.equal(replayPrimarySource.closed, true)
const beforeLateDelta = conversation.liveAnswerBuffer.value
firstPrimarySource.emit('model-delta', { ...delta, eventId: 'late-delta', content: 'late' })
assert.equal(conversation.liveAnswerBuffer.value, beforeLateDelta)

presentationStream.stop()
primaryStream.stop()
assert.ok(presentationSources.every(source => source.closed))
assert.ok(primarySources.every(source => source.closed))

console.log('workbench P3 transport and answer-state smoke passed')
