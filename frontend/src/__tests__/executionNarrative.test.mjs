import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { build } from 'esbuild'

const bundled = await build({
  entryPoints: [fileURLToPath(new URL('../utils/executionNarrative.ts', import.meta.url))],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
})
const source = Buffer.from(bundled.outputFiles[0].contents).toString('base64')
const { aggregateExecutionNarrative } = await import(`data:text/javascript;base64,${source}`)

/** 构造最小的公开 presentation（只填 aggregateExecutionNarrative 实际读取的字段）。 */
function presentation(overrides) {
  return {
    presentationId: 'p',
    workItemId: 'w1',
    sequence: 1,
    schemaVersion: 1,
    kind: 'TASK_UNDERSTANDING',
    status: 'ACTIVE',
    title: '',
    summary: '',
    steps: [],
    detail: { targetLabel: '', referenceType: '', referenceId: '', attributes: {} },
    sourceType: 'AGENT_RUN',
    sourceId: 'run-1',
    occurredAt: '2026-10-18T07:57:33Z',
    visibility: 'PUBLIC',
    ...overrides,
  }
}

const turnCompleted = {
  turnId: 'turn-1', conversationId: 'c1', inputId: 'i1', workItemId: 'w1',
  userMessage: '', executionTarget: 'PROCUREMENT_SOURCING',
  controlState: 'CLOSED', executionState: 'COMPLETED', outcome: 'SUCCESS',
  activeRunId: '', activeIncidentId: '', activePlanId: '', createdAt: '2026-10-18T07:57:33Z',
}

/* ------------------------------------------------------------------ */
/* 1. 终态下，前面阶段后面有已完成步骤 → 应推断为 completed，而不是 unresolved */
/*    这是用户截图里的问题：任务完成了，「正在理解目标」却报「未收到完成记录」 */
/* ------------------------------------------------------------------ */
{
  const groups = aggregateExecutionNarrative(turnCompleted, [
    presentation({ presentationId: 'p1', kind: 'TASK_UNDERSTANDING', status: 'ACTIVE', sequence: 1, summary: '正在理解目标' }),
    presentation({ presentationId: 'p2', kind: 'ROUTE_SUMMARY', status: 'COMPLETED', sequence: 2, summary: '系统已选择 Procurement Sourcing Agent' }),
  ], null)

  const items = groups[0].items
  const understanding = items.find(item => item.summary === '正在理解目标')
  assert.ok(understanding, '应存在「正在理解目标」条目')
  assert.equal(understanding.status, 'completed',
    '后续步骤已完成，前面这一步应推断为已完成，而不是「未收到完成记录」')
}

/* ------------------------------------------------------------------ */
/* 2. 终态下，末尾仍未完成的步骤（后面没有已完成项）→ 保持 unresolved（诚实） */
/* ------------------------------------------------------------------ */
{
  const groups = aggregateExecutionNarrative(turnCompleted, [
    presentation({ presentationId: 'p1', kind: 'ROUTE_SUMMARY', status: 'COMPLETED', sequence: 1, summary: '已选择 Agent' }),
    presentation({ presentationId: 'p2', kind: 'ACTION_STARTED', status: 'ACTIVE', sequence: 2, summary: '已开始执行任务' }),
  ], null)

  const items = groups[0].items
  const started = items.find(item => item.summary === '已开始执行任务')
  assert.ok(started)
  assert.equal(started.status, 'unresolved',
    '末尾步骤确实没有完成证据，应保持「结果待核对」')
}

/* ------------------------------------------------------------------ */
/* 3. 运行中（非终态）：未完成阶段保持 active，不应被推断成 completed      */
/* ------------------------------------------------------------------ */
{
  const running = { ...turnCompleted, executionState: 'RUNNING', controlState: 'ACTIVE' }
  const groups = aggregateExecutionNarrative(running, [
    presentation({ presentationId: 'p1', kind: 'TASK_UNDERSTANDING', status: 'ACTIVE', sequence: 1, summary: '正在理解目标' }),
    presentation({ presentationId: 'p2', kind: 'ROUTE_SUMMARY', status: 'COMPLETED', sequence: 2, summary: '已选择 Agent' }),
  ], null)

  const understanding = groups[0].items.find(item => item.summary === '正在理解目标')
  assert.equal(understanding.status, 'active', '运行中不应提前把活动步骤标记为完成')
}

console.log('executionNarrative smoke passed (3 cases: 中间步骤推断完成、末尾步骤保持待核对、运行中不推断)')
