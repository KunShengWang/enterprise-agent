import type { PublicPresentation, WorkEvent } from '../types/workbench'

export type ActivityFilter = 'all' | 'error' | 'tool' | 'model' | 'approval' | 'recovery'
export type ActivityCategory = 'intake' | 'routing' | 'dispatch' | 'context' | 'model' | 'tool'
  | 'agents' | 'approval' | 'recovery' | 'budget' | 'result'

const labels: Record<ActivityCategory, string> = {
  intake: '输入与 WorkItem', routing: '路由', dispatch: '派发', context: '上下文', model: '模型',
  tool: '工具', agents: 'Agent 协作', approval: '审批', recovery: '重试与恢复', budget: '预算', result: '最终结果',
}

function text(event: WorkEvent) {
  return `${event.eventType} ${event.phase ?? ''} ${event.summary}`.toUpperCase()
}

export function activityCategory(event: WorkEvent): ActivityCategory {
  const value = text(event)
  if (/(BUDGET_|QUOTA_|COST_)/.test(value)) return 'budget'
  if (/(FENCING|LEASE_|RECONCILIATION|RECOVERY|RETRY|UNKNOWN|TAKEOVER)/.test(value)) return 'recovery'
  if (/(APPROVAL|CONFIRMATION|PAUSE|RESUME|WAITING_INPUT)/.test(value)) return 'approval'
  if (/(TOOL_|POLICY_)/.test(value)) return 'tool'
  if (/(AGENT_|TASK_|INCIDENT_|REVIEWER|COMMANDER|SPECIALIST|PLANNER)/.test(value)) return 'agents'
  if (/(MODEL_|RUN_STARTED|RUN_CREATED)/.test(value)) return 'model'
  if (value.includes('CONTEXT_')) return 'context'
  if (/(DISPATCH_|EXECUTION_DISPATCHED)/.test(value)) return 'dispatch'
  if (/(ROUTING_|ROUTE_)/.test(value)) return 'routing'
  if (/(WORK_ITEM_CREATED|INPUT_)/.test(value)) return 'intake'
  return 'result'
}

function matchesFilter(event: WorkEvent, filter: ActivityFilter) {
  const value = text(event)
  if (filter === 'all') return true
  if (filter === 'error') return /(ERROR|FAILED|REJECTED|EXPIRED|EXHAUSTED)/.test(value)
  if (filter === 'tool') return activityCategory(event) === 'tool'
  if (filter === 'model') return activityCategory(event) === 'model'
  if (filter === 'approval') return activityCategory(event) === 'approval'
  return activityCategory(event) === 'recovery'
}

export function projectActivity(events: WorkEvent[], filter: ActivityFilter, query: string) {
  const keyword = query.trim().toLowerCase()
  const sorted = [...events].sort((left, right) => left.sequence - right.sequence)
    .filter(event => matchesFilter(event, filter))
    .filter(event => !keyword || `${event.summary} ${event.phase} ${event.eventType} ${event.sourceType} ${event.sourceId}`
      .toLowerCase().includes(keyword))
  const grouped = new Map<ActivityCategory, WorkEvent[]>()
  for (const event of sorted) {
    const category = activityCategory(event)
    grouped.set(category, [...(grouped.get(category) ?? []), event])
  }
  return [...grouped].map(([id, values]) => ({ id, label: labels[id], events: values }))
}

export function projectTools(presentations: PublicPresentation[]) {
  const tools = new Map<string, PublicPresentation>()
  presentations.filter(item => item.visibility !== 'INTERNAL' && item.kind === 'TOOL_ACTIVITY' && item.detail.tool).forEach(item => {
    const id = item.detail.referenceId || item.presentationId
    const current = tools.get(id)
    if (!current || item.sequence > current.sequence) {
      const publicArguments = Object.keys(item.detail.tool?.publicArguments ?? {}).length
        ? item.detail.tool?.publicArguments ?? {} : current?.detail.tool?.publicArguments ?? {}
      tools.set(id, { ...item, detail: { ...item.detail, tool: { ...item.detail.tool!, publicArguments } } })
    }
  })
  return [...tools.entries()].map(([toolCallId, item]) => ({ toolCallId, item }))
    .sort((left, right) => left.item.sequence - right.item.sequence)
}

export function projectorLagMs(events: WorkEvent[]) {
  return events.reduce((maximum, event) => {
    if (!event.projectedAt || !event.sourceCreatedAt) return maximum
    return Math.max(maximum, Math.max(0,
      new Date(event.projectedAt).getTime() - new Date(event.sourceCreatedAt).getTime()))
  }, 0)
}

export function diagnosticEvents(events: WorkEvent[]) {
  return [...events].filter(event => /(UNKNOWN|RECONCILIATION|LEASE_|TAKEOVER|FENCING|BUDGET_EXHAUSTED|RETRY|RECOVERY|FAILED|ERROR)/
    .test(text(event))).sort((left, right) => left.sequence - right.sequence)
}

const sensitiveKey = /password|passwd|secret|token|authorization|cookie|api.?key|system.?prompt|prompt|reasoning|chain.?of.?thought/i

export function sanitizeInspectorPayload(value: unknown, depth = 0): unknown {
  if (depth > 6) return '[depth-limited]'
  if (Array.isArray(value)) return value.slice(0, 100).map(item => sanitizeInspectorPayload(item, depth + 1))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, nested]) =>
      [key, sensitiveKey.test(key) ? '[redacted]' : sanitizeInspectorPayload(nested, depth + 1)]))
  }
  if (typeof value === 'string' && value.length > 8_000) return `${value.slice(0, 8_000)}…`
  return value
}
