import { ref } from 'vue'
import { agentApi } from '../api/agent'
import { workbenchApi } from '../api/workbench'
import type { AgentConversationMessage, ApprovalRecord } from '../types/agent'
import type { WorkExecutionTree, WorkFocus, WorkInput, WorkItem, WorkItemBudget, WorkItemDetail } from '../types/workbench'

export function useWorkbenchData() {
  const inputs = ref<WorkInput[]>([])
  const workItems = ref<WorkItem[]>([])
  const messages = ref<AgentConversationMessage[]>([])
  const focus = ref<WorkFocus | null>(null)
  const detail = ref<WorkItemDetail | null>(null)
  const tree = ref<WorkExecutionTree | null>(null)
  const budget = ref<WorkItemBudget | null>(null)
  const approval = ref<ApprovalRecord | null>(null)
  let generation = 0

  async function loadConversation(conversationId: string) {
    const token = ++generation
    const [nextInputs, nextItems, nextMessages] = await Promise.all([
      workbenchApi.inputs(conversationId), workbenchApi.workItems(conversationId),
      agentApi.conversationMessages(conversationId, 500),
    ])
    if (token !== generation) return false
    inputs.value = nextInputs
    workItems.value = nextItems
    messages.value = nextMessages
    try {
      const nextFocus = await workbenchApi.focus(conversationId)
      if (token === generation) focus.value = nextFocus
    } catch { if (token === generation) focus.value = null }
    return token === generation
  }

  async function loadSelected(workItemId: string) {
    const token = generation
    const [nextDetail, nextTree, nextBudget] = await Promise.all([
      workbenchApi.detail(workItemId), workbenchApi.executionTree(workItemId).catch(() => null),
      workbenchApi.budget(workItemId).catch(() => null),
    ])
    if (token !== generation || nextDetail.workItem.workItemId !== workItemId) return false
    detail.value = nextDetail
    tree.value = nextTree
    budget.value = nextBudget
    if (nextDetail.workItem.activeRunId) {
      const approvals = await agentApi.approvals(100)
      if (token !== generation) return false
      approval.value = approvals.find(item => item.runId === nextDetail.workItem.activeRunId
        && item.status === 'REQUESTED') ?? null
    } else approval.value = null
    return true
  }

  async function refreshMessages(conversationId: string) {
    const token = generation
    const next = await agentApi.conversationMessages(conversationId, 500)
    if (token !== generation) return false
    messages.value = next
    return true
  }

  async function refreshTree(workItemId: string) {
    const token = generation
    const next = await workbenchApi.executionTree(workItemId).catch(() => null)
    if (token !== generation || detail.value?.workItem.workItemId !== workItemId) return false
    tree.value = next
    return true
  }

  function clear() {
    generation += 1
    inputs.value = []
    workItems.value = []
    messages.value = []
    focus.value = null
    detail.value = null
    tree.value = null
    budget.value = null
    approval.value = null
  }

  return {
    inputs, workItems, messages, focus, detail, tree, budget, approval,
    loadConversation, loadSelected, refreshMessages, refreshTree, clear,
    invalidate: () => { generation += 1 },
  }
}
