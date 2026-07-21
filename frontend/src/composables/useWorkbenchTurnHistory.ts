import { computed, ref } from 'vue'
import { agentApi } from '../api/agent'
import { workbenchApi } from '../api/workbench'
import type { AgentConversationMessage, ApprovalRecord } from '../types/agent'
import type { PrimaryAnswerView } from '../types/conversation'
import type {
  ConversationTurn, PublicPresentation, TurnExecutionSnapshot, WorkEvent, WorkExecutionTree,
  WorkInput, WorkItem, WorkItemBudget, WorkItemDetail,
} from '../types/workbench'
import { projectConversationTurns } from '../utils/conversationTurns'
import { incidentAssessmentMarkdown } from '../utils/incidentAssessment'
import { isToolCallProtocolEnvelope, normalizeAssistantContent } from '../utils/publicContent'

function terminalState(work: WorkItem) {
  const value = `${work.controlState} ${work.executionState} ${work.outcome}`.toUpperCase()
  if (value.includes('CANCELLED')) return 'CANCELLED' as const
  if (value.includes('FAILED')) return 'FAILED' as const
  if (/(COMPLETED|CLOSED|RESOLVED|ASSESSED)/.test(value)) return 'COMPLETED' as const
  return 'WAITING' as const
}

function persistedAnswer(work: WorkItem, messages: AgentConversationMessage[], tree: WorkExecutionTree | null): PrimaryAnswerView {
  const message = messages.filter(item => item.role === 'ASSISTANT' && item.runId === work.activeRunId)
    .sort((left, right) => right.sequence - left.sequence)[0]
  if (message) {
    const content = normalizeAssistantContent(message.content)
    if (content && !isToolCallProtocolEnvelope(content)) return {
      state: 'COMPLETED', content, persistedMessageId: message.messageId, createdAt: message.createdAt,
    }
  }
  if (work.activeExecutionTarget === 'INCIDENT_INVESTIGATION' && work.outcome.toUpperCase() === 'ASSESSED') {
    const content = incidentAssessmentMarkdown(tree)
    if (content) return {
      state: 'COMPLETED', content,
      persistedMessageId: `projected-assessment-${work.workItemId}`, createdAt: work.updatedAt,
    }
  }
  return { state: terminalState(work), content: '', persistedMessageId: '', createdAt: work.updatedAt }
}

async function mapBounded<T>(values: T[], workers: number, operation: (value: T) => Promise<void>) {
  let index = 0
  await Promise.all(Array.from({ length: Math.min(workers, values.length) }, async () => {
    while (index < values.length) {
      const current = values[index]
      index += 1
      await operation(current)
    }
  }))
}

export function useWorkbenchTurnHistory() {
  const turns = ref<ConversationTurn[]>([])
  const snapshots = ref<Record<string, TurnExecutionSnapshot>>({})
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  let activeConversationId = ''

  const orderedSnapshots = computed(() => turns.value
    .map(turn => snapshots.value[turn.turnId])
    .filter((value): value is TurnExecutionSnapshot => Boolean(value)))

  async function hydrate(conversationId: string, inputs: WorkInput[], workItems: WorkItem[],
                         messages: AgentConversationMessage[], forceWorkItemId = '') {
    if (activeConversationId !== conversationId) {
      activeConversationId = conversationId
      snapshots.value = {}
    }
    const token = ++generation
    const nextTurns = projectConversationTurns(inputs, workItems)
    turns.value = nextTurns
    loading.value = true
    error.value = ''
    let approvals: ApprovalRecord[] = []
    try { approvals = await agentApi.approvals(500) } catch { approvals = [] }
    if (token !== generation) return false
    const nextSnapshots = { ...snapshots.value }
    try {
      await mapBounded(nextTurns, 6, async turn => {
        if (token !== generation) return
        const existing = nextSnapshots[turn.turnId]
        const currentWork = workItems.find(item => item.workItemId === turn.workItemId)!
        const unchangedTerminal = existing
          && existing.detail.workItem.version === currentWork.version
          && terminalState(currentWork) !== 'WAITING'
          && currentWork.workItemId !== forceWorkItemId
        if (unchangedTerminal) {
          nextSnapshots[turn.turnId] = { ...existing, turn }
          return
        }
        const [detail, publicPresentations, inspectorPresentations, tree, budget] = await Promise.all([
          workbenchApi.detail(turn.workItemId),
          workbenchApi.presentations(turn.workItemId, -1, 500),
          workbenchApi.inspectorPresentations(turn.workItemId, -1, 500),
          workbenchApi.executionTree(turn.workItemId).catch(() => null),
          workbenchApi.budget(turn.workItemId).catch(() => null),
        ])
        if (token !== generation) return
        const approval = approvals.find(item => item.runId === detail.workItem.activeRunId) ?? null
        nextSnapshots[turn.turnId] = {
          turn, detail, publicPresentations, inspectorPresentations,
          events: detail.events, tree, budget, approval,
          answer: persistedAnswer(detail.workItem, messages, tree),
        }
      })
      if (token !== generation) return false
      snapshots.value = nextSnapshots
      return true
    } catch (cause) {
      if (token === generation) error.value = cause instanceof Error ? cause.message : 'Turn history load failed'
      return false
    } finally {
      if (token === generation) loading.value = false
    }
  }

  function mergeActive(turnId: string,
                       detail: WorkItemDetail,
                       publicPresentations: PublicPresentation[],
                       inspectorPresentations: PublicPresentation[],
                       events: WorkEvent[],
                       tree: WorkExecutionTree | null,
                       budget: WorkItemBudget | null,
                       approval: ApprovalRecord | null,
                       answer: PrimaryAnswerView) {
    const turn = turns.value.find(item => item.turnId === turnId)
    if (!turn || turn.workItemId !== detail.workItem.workItemId) return false
    snapshots.value = {
      ...snapshots.value,
      [turnId]: { turn, detail, publicPresentations: [...publicPresentations],
        inspectorPresentations: [...inspectorPresentations], events: [...events], tree, budget, approval, answer },
    }
    return true
  }

  function invalidate() { generation += 1 }
  function clear() {
    generation += 1
    activeConversationId = ''
    turns.value = []
    snapshots.value = {}
    loading.value = false
    error.value = ''
  }

  return { turns, snapshots, orderedSnapshots, loading, error, hydrate, mergeActive, invalidate, clear }
}
