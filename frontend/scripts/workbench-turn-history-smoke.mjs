import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

globalThis.window = { setTimeout, clearTimeout }

async function loadModule(relativePath) {
  const bundled = await build({
    entryPoints: [fileURLToPath(new URL(relativePath, import.meta.url))],
    bundle: true, format: 'esm', platform: 'node', write: false,
    define: { 'import.meta.env': '{}' },
  })
  const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
  return import(`data:text/javascript;base64,${source}`)
}

const now = index => `2026-07-21T00:00:0${index}Z`
const work = index => ({
  workItemId: `work-${index}`, conversationId: 'conversation-1', sourceInputId: `input-${index}`,
  originalGoal: `goal-${index}`, activeExecutionTarget: index === 3 ? 'INCIDENT_INVESTIGATION' : 'GENERAL_AGENT',
  activeRunId: `run-${index}`, activeIncidentId: index === 3 ? 'incident-3' : '', activeRecoveryPlanId: '',
  controlState: 'CLOSED', executionState: 'COMPLETED', outcome: index === 3 ? 'ASSESSED' : 'RESOLVED',
  routingFailureCode: '', version: 2, createdAt: now(index), updatedAt: now(index + 1), completedAt: now(index + 1),
})
const input = index => ({ inputId: `input-${index}`, clientInputId: `client-${index}`,
  conversationId: 'conversation-1', content: `user-${index}`, inputKind: 'NORMAL_GOAL',
  classificationStatus: 'CLASSIFIED', createdAt: now(index) })
const presentation = (workItemId, id, sequence, kind, title, summary, visibility = 'PUBLIC') => ({
  presentationId: id, workItemId, sequence, schemaVersion: 1, kind, status: 'COMPLETED', title, summary,
  steps: [], detail: { targetLabel: '', referenceType: '', referenceId: '', attributes: {} },
  sourceType: 'WORK_ITEM', sourceId: workItemId, sourceEventId: `event-${id}`,
  occurredAt: now(Math.min(sequence, 9)), visibility,
})

const { projectConversationTurns } = await loadModule('../src/utils/conversationTurns.ts')
const inputs = [input(1), input(2), input(3), {
  ...input(4), inputId: 'command-1', inputKind: 'WORK_COMMAND', commandType: 'RESUME_ACTIVE_WORK',
}]
const works = [work(3), work(1), work(2)]
const turns = projectConversationTurns(inputs, works)
assert.deepEqual(turns.map(item => item.turnId), ['input-1', 'input-2', 'input-3'])
assert.deepEqual(turns.map(item => item.workItemId), ['work-1', 'work-2', 'work-3'])
assert.ok(!turns.some(item => item.turnId === 'command-1'))
assert.deepEqual(projectConversationTurns([...inputs].reverse(), [...works].reverse())
  .map(item => item.turnId), turns.map(item => item.turnId))

const { useTurnSelection } = await loadModule('../src/composables/useTurnSelection.ts')
const turnSelection = useTurnSelection()
turnSelection.synchronize(turns.slice(0, 2))
assert.equal(turnSelection.selectedTurnId.value, 'input-2')
turnSelection.select('input-1')
turnSelection.synchronize(turns)
assert.equal(turnSelection.selectedTurnId.value, 'input-1')
assert.equal(turnSelection.followCurrent.value, false)
turnSelection.follow(turns)
assert.equal(turnSelection.selectedTurnId.value, 'input-3')
assert.equal(turnSelection.followCurrent.value, true)

const { aggregateExecutionNarrative } = await loadModule('../src/utils/executionNarrative.ts')
const publicItems = [
  presentation('work-3', 'route', 1, 'ROUTE_SUMMARY', '已理解任务', '已识别为只读事故调查'),
  presentation('work-3', 'start-1', 2, 'ACTION_STARTED', '开始执行', '主 Agent 已开始处理任务。'),
  presentation('work-3', 'start-2', 3, 'ACTION_STARTED', '执行已启动', '正在启动执行。'),
  presentation('work-3', 'order', 4, 'AGENT_DELEGATION', '已派发 Order Specialist', 'Order 正在取证'),
  presentation('work-3', 'inventory', 5, 'AGENT_DELEGATION', '已派发 Inventory Specialist', 'Inventory 正在取证'),
  presentation('work-3', 'assessment', 6, 'FINAL_RESULT', '已生成事故 Assessment', '未执行恢复操作。'),
  presentation('work-3', 'private', 7, 'ACTION_COMPLETED', 'hidden reasoning', 'system prompt', 'INTERNAL'),
]
publicItems[3].detail = { ...publicItems[3].detail, targetLabel: 'Order', referenceType: 'INCIDENT_TASK',
  referenceId: 'task-order', attributes: { role: 'Order', incidentId: 'incident-3', evidenceCount: '1', evidenceIds: 'evidence-order' } }
const incidentTree = { workItemId: 'work-3', executionTarget: 'INCIDENT_INVESTIGATION', treeType: 'INCIDENT',
  executionId: 'incident-3', agents: [], conflicts: [], assessment: { riskLevel: 'LOW',
    confirmedFacts: [{ statement: '三笔订单均已进入终态。' }] }, recoveryPlans: [], metrics: {},
  evidence: [{ evidenceId: 'evidence-order', taskId: 'task-order', childRunId: 'run-order',
    evidenceClass: 'FACT', evidenceSubtype: 'ORDER_STATUS_SET', sourceSystem: 'floworder',
    observedAt: now(4), status: 'ACCEPTED', facts: { terminalDistinctRequestIdCount: 3,
      requestIds: ['REQ-1', 'REQ-2', 'REQ-3'] } }] }
const narrative = aggregateExecutionNarrative(turns[2], publicItems, incidentTree)
assert.equal(narrative.length, 1)
assert.equal(narrative[0].items.filter(item => item.id.includes('execution-started')).length, 1)
assert.equal(narrative[0].items.find(item => item.id.includes('execution-started')).sourcePresentationIds.length, 2)
assert.ok(narrative[0].sourcePresentationIds.includes('order'))
assert.ok(narrative[0].sourcePresentationIds.includes('inventory'))
assert.ok(!JSON.stringify(narrative).includes('hidden reasoning'))
assert.ok(!JSON.stringify(narrative).includes('system prompt'))
const orderNarrative = narrative[0].items.find(item => item.summary.includes('Order Specialist'))
assert.equal(orderNarrative.metadata.find(item => item.label === '证据数量').value, '1')
assert.ok(orderNarrative.metadata.some(item => item.label === 'Incident' && item.value === 'incident-3'))
assert.ok(orderNarrative.findings.some(item => item.includes('终态请求 3')))
assert.ok(orderNarrative.findings.some(item => item.includes('REQ-1')))

const { projectTurnConversationItems } = await loadModule('../src/utils/conversationItems.ts')
const turnViews = turns.map((turn, index) => {
  const item = works.find(value => value.workItemId === turn.workItemId)
  const detail = { workItem: item, links: [], events: [] }
  const answer = { state: 'COMPLETED', content: `answer-${index + 1}`,
    persistedMessageId: `message-${index + 1}`, createdAt: item.updatedAt }
  return projectTurnConversationItems({ turn, detail, inputs, presentations: index === 2 ? publicItems : [],
    approval: null, answer })
})
assert.deepEqual(turnViews.map(entries => entries.find(item => item.type === 'USER_MESSAGE').content),
  ['user-1', 'user-2', 'user-3'])
assert.deepEqual(turnViews.map(entries => entries.find(item => item.type === 'FINAL_ANSWER').content),
  ['answer-1', 'answer-2', 'answer-3'])
assert.equal(turnViews[2].filter(item => item.type === 'EXECUTION_NARRATIVE').length, 1)
assert.equal(turnViews[0].filter(item => item.type === 'EXECUTION_NARRATIVE').length, 0)

const { eventsForInspectorScope, snapshotsForInspectorScope } = await loadModule('../src/utils/inspectorScope.ts')
const snapshot = (turn, zeroIndex) => { const index = zeroIndex + 1; return ({ turn, detail: { workItem: works.find(item => item.workItemId === turn.workItemId), links: [], events: [] },
  publicPresentations: [], inspectorPresentations: [], tree: null, budget: null, approval: null,
  answer: { state: 'COMPLETED', content: `answer-${index}`, persistedMessageId: '', createdAt: now(index) },
  events: [
    { eventId: `work-event-${index}`, workItemId: turn.workItemId, sequence: 1, sourceType: 'WORK_ITEM', sourceId: turn.workItemId, summary: '', projectedAt: now(index), payload: {} },
    { eventId: `old-run-${index}`, workItemId: turn.workItemId, sequence: 2, sourceType: 'AGENT_RUN', sourceId: `old-run-${index}`, summary: '', projectedAt: now(index), payload: {} },
    { eventId: `active-run-${index}`, workItemId: turn.workItemId, sequence: 3, sourceType: 'AGENT_RUN', sourceId: `run-${index}`, summary: '', projectedAt: now(index), payload: {} },
  ],
}) }
const snapshots = turns.map(snapshot)
assert.deepEqual(eventsForInspectorScope('TURN', snapshots[1]).map(item => item.eventId),
  ['work-event-2', 'active-run-2'])
assert.equal(eventsForInspectorScope('WORK_ITEM', snapshots[1]).length, 3)
assert.equal(snapshotsForInspectorScope('CONVERSATION', snapshots, 'input-2', snapshots[1]).length, 3)
assert.equal(snapshotsForInspectorScope('TURN', snapshots, 'input-2', snapshots[1]).length, 1)

const largeInputs = Array.from({ length: 100 }, (_, index) => ({ ...input(1), inputId: `large-input-${index}`,
  content: `goal-${index}`, createdAt: new Date(1_700_000_000_000 + index).toISOString() }))
const largeWorks = largeInputs.map((item, index) => ({ ...work(1), workItemId: `large-work-${index}`,
  sourceInputId: item.inputId, originalGoal: item.content, createdAt: item.createdAt, updatedAt: item.createdAt }))
const started = performance.now()
const largeTurns = projectConversationTurns(largeInputs, largeWorks)
assert.equal(largeTurns.length, 100)
assert.ok(performance.now() - started < 500)

const inspectorSource = await readFile(new URL('../src/components/ExecutionInspector.vue', import.meta.url), 'utf8')
const rendererSource = await readFile(new URL('../src/components/ConversationItemRenderer.vue', import.meta.url), 'utf8')
assert.ok(inspectorSource.includes("'TURN', '本轮'"))
assert.ok(inspectorSource.includes("'WORK_ITEM', '当前 WorkItem'"))
assert.ok(inspectorSource.includes("'CONVERSATION', '整个 Conversation'"))
assert.ok(inspectorSource.includes('scopeRequestKey'))
assert.ok(inspectorSource.includes('跟随当前执行'))
assert.ok(rendererSource.includes('@click="toggleNarrative(entry.id)"'))
assert.ok(rendererSource.includes('在检查器中打开'))
assert.ok(rendererSource.includes("presentationIds: entry.sourcePresentationIds"))
assert.ok(rendererSource.includes('execution-narrative-detail'))
assert.ok(!rendererSource.includes('JSON.stringify(entry'))
assert.ok(inspectorSource.includes('scopedPublicPresentations'))
assert.ok(inspectorSource.includes('resolveInspectorLocation'))
const workbenchSource = await readFile(new URL('../src/views/UnifiedWorkbench.vue', import.meta.url), 'utf8')
assert.ok(workbenchSource.includes('rightDrawerOpen.value = true'))
assert.ok(workbenchSource.includes('await selectTurn(locator.turnId)'))
const cssSource = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8')
assert.ok(cssSource.includes('.execution-narrative-list > li'))
assert.equal(cssSource.includes('.execution-narrative-list li { display: grid'), false)

const { resolveInspectorLocation } = await loadModule('../src/utils/inspectorLocation.ts')
const publicSource = { presentationId: 'public-specialist', sourceEventId: 'task-event-7',
  sourceType: 'INCIDENT', sourceId: 'incident-3' }
const technicalSource = { presentationId: 'technical-specialist', sourceEventId: 'task-event-7',
  sourceType: 'INCIDENT', sourceId: 'incident-3', kind: 'ACTION_COMPLETED' }
const exactTechnicalEvent = { eventId: 'work-event-7', sourceEventId: 'task-event-7',
  sourceType: 'INCIDENT', sourceId: 'incident-3' }
const unrelatedSameIncident = { eventId: 'work-event-1', sourceEventId: 'task-event-1',
  sourceType: 'INCIDENT', sourceId: 'incident-3' }
const location = resolveInspectorLocation(['public-specialist'], [publicSource], [technicalSource],
  [unrelatedSameIncident, exactTechnicalEvent])
assert.equal(location.presentations[0].presentationId, 'technical-specialist')
assert.equal(location.event.eventId, 'work-event-7')

console.log('workbench P6 turn history, inspector scope and narrative smoke passed')
