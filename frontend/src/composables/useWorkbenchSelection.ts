import { computed, ref } from 'vue'
import type { WorkItem } from '../types/workbench'

const CONVERSATION_KEY = 'unified-workbench-conversation'
const HISTORY_KEY = 'unified-workbench-conversations'

function storage() {
  return typeof localStorage === 'undefined' ? null : localStorage
}

function newConversationId() {
  const suffix = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID() : `${Date.now()}`
  return `workbench-${suffix}`
}

export function useWorkbenchSelection() {
  const conversationId = ref(storage()?.getItem(CONVERSATION_KEY) || newConversationId())
  const selectedWorkItemId = ref('')
  const history = ref<WorkItem[]>([])
  const search = ref('')

  function knownConversations() {
    try {
      const parsed = JSON.parse(storage()?.getItem(HISTORY_KEY) || '[]')
      return Array.isArray(parsed) ? parsed.filter(value => typeof value === 'string').slice(0, 20) : []
    } catch { return [] }
  }

  function rememberConversation(value: string) {
    const next = [value, ...knownConversations().filter(item => item !== value)].slice(0, 20)
    storage()?.setItem(HISTORY_KEY, JSON.stringify(next))
    storage()?.setItem(CONVERSATION_KEY, value)
  }

  rememberConversation(conversationId.value)

  const filteredHistory = computed(() => {
    const keyword = search.value.trim().toLowerCase()
    return history.value.filter(item => !keyword || [item.originalGoal, item.activeExecutionTarget, item.controlState]
      .some(value => String(value ?? '').toLowerCase().includes(keyword)))
  })

  function beginNewConversation() {
    conversationId.value = newConversationId()
    selectedWorkItemId.value = ''
    rememberConversation(conversationId.value)
    return conversationId.value
  }

  function select(item: WorkItem) {
    conversationId.value = item.conversationId
    selectedWorkItemId.value = item.workItemId
    rememberConversation(item.conversationId)
  }

  function replaceHistory(items: WorkItem[]) {
    const unique = new Map(items.map(item => [item.workItemId, item]))
    history.value = [...unique.values()].sort((left, right) =>
      new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
  }

  return {
    conversationId, selectedWorkItemId, history, filteredHistory, search,
    knownConversations, rememberConversation, beginNewConversation, select, replaceHistory,
  }
}
