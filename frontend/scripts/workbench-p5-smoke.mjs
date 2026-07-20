import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

async function loadModule(relativePath) {
  const bundled = await build({ entryPoints: [fileURLToPath(new URL(relativePath, import.meta.url))], bundle: true, format: 'esm', platform: 'node', write: false })
  return import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].contents).toString('base64')}`)
}
const { activityCategory, projectActivity, projectTools, diagnosticEvents, projectorLagMs, sanitizeInspectorPayload } = await loadModule('../src/utils/inspectorProjection.ts')
const event = (sequence, phase, summary = phase) => ({ eventId: `event-${sequence}`, workItemId: 'work-1', sequence, eventType: 'PROJECTED', phase, summary, projectedAt: `2026-07-21T00:00:${String(sequence + 1).padStart(2, '0')}Z`, sourceCreatedAt: `2026-07-21T00:00:${String(sequence).padStart(2, '0')}Z`, sourceType: 'AGENT_RUN', sourceId: 'run-1', sourceSequence: sequence, correlationId: 'corr-1', causationId: 'cause-1', payload: {} })
const events = [event(9, 'RUN_COMPLETED'), event(1, 'WORK_ITEM_CREATED'), event(2, 'ROUTING_DECIDED'), event(3, 'DISPATCH_STARTED'), event(4, 'CONTEXT_PROJECTED'), event(5, 'MODEL_TURN_STARTED'), event(6, 'TOOL_COMPLETED'), event(7, 'APPROVAL_REQUESTED'), event(8, 'RECONCILIATION_STARTED')]
assert.deepEqual(events.slice(1).map(activityCategory), ['intake', 'routing', 'dispatch', 'context', 'model', 'tool', 'approval', 'recovery'])
const groups = projectActivity(events, 'all', '')
assert.equal(groups.flatMap(group => group.events).map(item => item.sequence).join(','), '1,2,3,4,5,6,7,8,9')
assert.deepEqual(projectActivity(events, 'tool', '').flatMap(group => group.events).map(item => item.sequence), [6])
assert.deepEqual(projectActivity(events, 'all', 'approval').flatMap(group => group.events).map(item => item.sequence), [7])
assert.deepEqual(diagnosticEvents([...events, event(10, 'FENCING_REJECTED'), event(11, 'BUDGET_EXHAUSTED')]).map(item => item.sequence), [8, 10, 11])
assert.equal(projectorLagMs(events), 1000)

const tool = (id, sequence, visibility, status, args = {}) => ({ presentationId: id, workItemId: 'work-1', sequence, kind: 'TOOL_ACTIVITY', status, visibility, sourceType: 'AGENT_RUN', sourceId: 'run-1', detail: { referenceId: 'call-1', tool: { toolName: 'knowledge_search', displayName: '知识检索', actionSummary: '检索知识', publicArguments: args, resultSummary: status === 'COMPLETED' ? '返回 4 条结果' : '正在执行', resultCount: status === 'COMPLETED' ? 4 : undefined, durationMs: 386, attemptLabel: 'Attempt 1' } } })
const tools = projectTools([tool('request', 1, 'PUBLIC', 'ACTIVE', { query: 'Spring' }), tool('result', 2, 'INSPECTOR_ONLY', 'COMPLETED'), tool('internal', 3, 'INTERNAL', 'FAILED', { secret: 'hidden' })])
assert.equal(tools.length, 1)
assert.equal(tools[0].item.status, 'COMPLETED')
assert.deepEqual(tools[0].item.detail.tool.publicArguments, { query: 'Spring' })
assert.equal(tools[0].item.detail.tool.resultCount, 4)

const sanitized = sanitizeInspectorPayload({ token: 'secret', nested: { systemPrompt: 'hidden', traceId: 'trace-1' }, normal: 'visible' })
assert.deepEqual(sanitized, { token: '[redacted]', nested: { systemPrompt: '[redacted]', traceId: 'trace-1' }, normal: 'visible' })

const many = Array.from({ length: 10_000 }, (_, index) => event(index, index % 10 === 0 ? 'TOOL_COMPLETED' : 'MODEL_TURN_STARTED'))
const started = performance.now()
assert.equal(projectActivity(many, 'all', '').flatMap(group => group.events).length, 10_000)
assert.ok(performance.now() - started < 1000)

const inspectorSource = await readFile(fileURLToPath(new URL('../src/components/ExecutionInspector.vue', import.meta.url)), 'utf8')
for (const tab of ['Activity', 'Agents', 'Tools', 'Evidence', 'Diagnostics']) assert.ok(inspectorSource.includes(tab))
assert.ok(inspectorSource.includes('EventPayloadDrawer'))
assert.ok(inspectorSource.includes('tree?.coordinator'))
assert.ok(inspectorSource.includes('syncError'))

console.log('workbench P5 inspector projection smoke passed')
