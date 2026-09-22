import type { ConversationItemStatus, ExecutionNarrativeGroup, ExecutionNarrativeItem } from '../types/conversation'
import type { ConversationTurn, PublicPresentation, WorkExecutionTree } from '../types/workbench'

function status(value: PublicPresentation['status']): ConversationItemStatus {
  if (value === 'FAILED') return 'failed'
  if (value === 'WAITING') return 'waiting'
  if (value === 'PENDING') return 'pending'
  if (value === 'ACTIVE') return 'active'
  return 'completed'
}

function detailText(item: PublicPresentation) {
  if (item.steps.length) return item.steps.join(' · ')
  return item.summary === item.title ? '' : item.summary
}

function normalizedAction(item: PublicPresentation, turn: ConversationTurn) {
  const title = `${item.title} ${item.summary}`.toLowerCase()
  if (item.kind === 'ROUTE_SUMMARY') {
    return { key: 'route', summary: item.summary || `已选择 ${turn.executionTarget}`, detail: item.title }
  }
  if (item.kind === 'TASK_UNDERSTANDING') {
    return { key: 'understanding', summary: item.summary || '已理解任务目标', detail: detailText(item) }
  }
  if (item.kind === 'STANDARD_PROCESS') {
    return { key: 'standard-process', summary: '已采用标准执行流程', detail: detailText(item) }
  }
  if (item.kind === 'EXECUTION_PLAN') {
    return { key: 'execution-plan', summary: item.title || '已生成公开执行计划', detail: detailText(item) }
  }
  if (item.kind === 'AGENT_DELEGATION') {
    const role = item.detail.attributes.role || item.detail.targetLabel || item.presentationId
    return { key: `agent-delegation-${role}`, summary: item.title || '已调度领域 Agent', detail: item.summary }
  }
  if (item.kind === 'RETRY') {
    return { key: `retry-${item.detail.referenceId || item.presentationId}`, summary: item.title, detail: item.summary }
  }
  if (item.kind === 'RECOVERY') {
    return { key: `recovery-${item.detail.referenceId || item.presentationId}`, summary: item.title, detail: item.summary }
  }
  if (item.kind === 'FINAL_RESULT') {
    return { key: 'final-result', summary: item.title || '已生成最终结果', detail: item.summary }
  }
  if (item.kind !== 'ACTION_STARTED' && item.kind !== 'ACTION_COMPLETED') return null
  if (/上下文|context/.test(title)) {
    return { key: 'context', summary: '已加载任务所需上下文', detail: item.summary }
  }
  if (/启动执行|执行已启动|开始执行|主 agent/.test(title)) {
    return { key: 'execution-started', summary: '已开始执行任务', detail: item.summary }
  }
  if (/调查范围/.test(title)) {
    return { key: 'incident-scope', summary: item.title, detail: item.summary }
  }
  if (/specialist|取证|reviewer|assessment|证据|一致性|恢复/.test(title)) {
    return { key: `${item.kind}-${item.title}`, summary: item.title, detail: item.summary }
  }
  return { key: `${item.kind}-${item.title}`, summary: item.title || item.summary, detail: item.summary }
}

function semanticMetadata(item: PublicPresentation, turn: ConversationTurn) {
  const attributes = item.detail.attributes
  const actor = attributes.actorType || (item.sourceType === 'AGENT_RUN' ? 'Agent Runtime'
    : item.sourceType === 'INCIDENT' ? 'Incident Orchestrator' : 'Workbench')
  const role = attributes.role || item.detail.targetLabel || (item.kind === 'ROUTE_SUMMARY' ? turn.executionTarget : '')
  const category = attributes.eventCategory || ({
    ROUTE_SUMMARY: '路由', STANDARD_PROCESS: '标准流程', EXECUTION_PLAN: '公开计划',
    AGENT_DELEGATION: 'Agent 调度', ACTION_STARTED: '执行', ACTION_COMPLETED: '执行',
    FINAL_RESULT: '结果', RETRY: '重试', RECOVERY: '恢复',
  } as Record<string, string>)[item.kind] || item.kind
  const metadata: Array<{ label: string; value: string; code?: boolean }> = [
    { label: '执行主体', value: actor },
    { label: '角色', value: role },
    { label: '状态', value: item.status },
    { label: '事件类别', value: category },
    { label: '发生时间', value: item.occurredAt },
  ].filter(value => Boolean(value.value))
  if (attributes.evidenceCount) metadata.push({ label: '证据数量', value: attributes.evidenceCount })
  if (item.detail.referenceType === 'INCIDENT_TASK') {
    metadata.push({ label: '子 Agent / Task', value: item.detail.referenceId, code: true })
  }
  for (const [key, label] of [['incidentId', 'Incident'], ['evidenceIds', 'Evidence IDs'],
    ['requestIds', 'Request IDs'], ['queueNames', 'Queue Names']] as const) {
    if (attributes[key]) metadata.push({ label, value: attributes[key], code: true })
  }
  if (attributes.sideEffectExecuted === 'false') metadata.push({ label: '副作用', value: '未执行恢复操作' })
  return metadata
}

export function aggregateExecutionNarrative(turn: ConversationTurn,
                                            presentations: PublicPresentation[],
                                            _tree?: WorkExecutionTree | null): ExecutionNarrativeGroup[] {
  const publicItems = presentations
    .filter(item => item.visibility === 'PUBLIC')
    .filter(item => !['TOOL_ACTIVITY', 'APPROVAL_REQUIRED', 'WAITING_FOR_USER', 'ERROR'].includes(item.kind))
    .sort((left, right) => left.sequence - right.sequence)
  const merged = new Map<string, ExecutionNarrativeItem>()
  for (const presentation of publicItems) {
    const action = normalizedAction(presentation, turn)
    if (!action) continue
    const existing = merged.get(action.key)
    const sourceIds = [...new Set([...(existing?.sourcePresentationIds ?? []), presentation.presentationId])]
    merged.set(action.key, {
      id: `narrative-${turn.turnId}-${action.key}`,
      status: status(presentation.status),
      summary: action.summary,
      detail: action.detail,
      sourcePresentationIds: sourceIds,
      occurredAt: existing?.occurredAt ?? presentation.occurredAt,
      metadata: semanticMetadata(presentation, turn),
      findings: [],
    })
  }
  const items = [...merged.values()]
  if (!items.length) return []
  const groupStatus: ConversationItemStatus = items.some(item => item.status === 'failed') ? 'failed'
    : items.some(item => item.status === 'waiting') ? 'waiting'
      : items.some(item => item.status === 'active') ? 'active' : 'completed'
  const sourcePresentationIds = items.flatMap(item => item.sourcePresentationIds)
  return [{
    groupId: `execution-narrative-${turn.turnId}`,
    turnId: turn.turnId,
    title: 'Agent 执行记录',
    status: groupStatus,
    summary: `${items.length} 个公开执行步骤`,
    items,
    sourcePresentationIds,
    startedAt: items[0].occurredAt,
    completedAt: items.at(-1)?.occurredAt ?? items[0].occurredAt,
    expandable: items.length > 1,
  }]
}
