import { computed, ref, type Ref } from 'vue'
import type { AgentConversationMessage, ApprovalRecord } from '../types/agent'
import type { PrimaryAnswerState, PrimaryAnswerView } from '../types/conversation'
import type { PublicPresentation, WorkInput, WorkItemDetail, WorkStreamItem } from '../types/workbench'
import { projectConversationItems } from '../utils/conversationItems'

export interface WorkbenchConversationSources {
  detail: Ref<WorkItemDetail | null>
  inputs: Ref<WorkInput[]>
  presentations: Ref<PublicPresentation[]>
  approval: Ref<ApprovalRecord | null>
}

export function useWorkbenchConversation(sources: WorkbenchConversationSources) {
  const workItemId = ref('')
  const primaryRunId = ref('')
  const liveAnswerBuffer = ref('')
  const persistedAnswer = ref<AgentConversationMessage | null>(null)
  const answerState = ref<PrimaryAnswerState>('IDLE')
  const answerCreatedAt = ref('')

  const answer = computed<PrimaryAnswerView>(() => ({
    state: answerState.value,
    content: persistedAnswer.value?.content ?? liveAnswerBuffer.value,
    persistedMessageId: persistedAnswer.value?.messageId ?? '',
    createdAt: persistedAnswer.value?.createdAt ?? answerCreatedAt.value,
  }))

  const entries = computed(() => sources.detail.value ? projectConversationItems({
    detail: sources.detail.value,
    inputs: sources.inputs.value,
    presentations: sources.presentations.value,
    approval: sources.approval.value,
    answer: answer.value,
  }) : [])

  function prepareWork(nextWorkItemId: string, runId = '', waiting = false) {
    if (workItemId.value !== nextWorkItemId) {
      workItemId.value = nextWorkItemId
      primaryRunId.value = runId
      liveAnswerBuffer.value = ''
      persistedAnswer.value = null
      answerCreatedAt.value = ''
      answerState.value = waiting ? 'WAITING' : 'IDLE'
      return
    }
    bindAuthoritativeRun(runId)
    if (waiting && answerState.value === 'IDLE') answerState.value = 'WAITING'
  }

  function bindAuthoritativeRun(runId: string) {
    if (!runId) return true
    if (primaryRunId.value && primaryRunId.value !== runId) {
      primaryRunId.value = runId
      liveAnswerBuffer.value = ''
      persistedAnswer.value = null
      answerCreatedAt.value = ''
      answerState.value = 'WAITING'
      return true
    }
    primaryRunId.value = runId
    return true
  }

  function applyDelta(event: WorkStreamItem) {
    if (!event.sourceId) return false
    if (primaryRunId.value && event.sourceId !== primaryRunId.value) return false
    if (!primaryRunId.value) primaryRunId.value = event.sourceId
    if (persistedAnswer.value) return false
    liveAnswerBuffer.value += event.content
    answerCreatedAt.value ||= event.createdAt
    answerState.value = 'STREAMING'
    return true
  }

  function applyPersisted(messages: AgentConversationMessage[], authoritativeRunId = '') {
    bindAuthoritativeRun(authoritativeRunId)
    if (!primaryRunId.value) return false
    const message = messages.filter(item => item.role === 'ASSISTANT' && item.runId === primaryRunId.value)
      .sort((left, right) => right.sequence - left.sequence)[0]
    if (!message) return false
    persistedAnswer.value = message
    liveAnswerBuffer.value = message.content
    answerCreatedAt.value = message.createdAt
    answerState.value = 'COMPLETED'
    return true
  }

  function markTerminal(state: 'COMPLETED' | 'FAILED' | 'CANCELLED') {
    if (state === 'COMPLETED') {
      if (!persistedAnswer.value) answerState.value = 'FINALIZING'
      return
    }
    if (!persistedAnswer.value) answerState.value = state
  }

  function beginWaiting() {
    if (!persistedAnswer.value && answerState.value === 'IDLE') answerState.value = 'WAITING'
  }

  function restartLiveReplay() {
    if (persistedAnswer.value) return
    liveAnswerBuffer.value = ''
    answerCreatedAt.value = ''
    answerState.value = 'WAITING'
  }

  function reset() {
    workItemId.value = ''
    primaryRunId.value = ''
    liveAnswerBuffer.value = ''
    persistedAnswer.value = null
    answerCreatedAt.value = ''
    answerState.value = 'IDLE'
  }

  return {
    entries, answer, answerState, liveAnswerBuffer, primaryRunId,
    prepareWork, bindAuthoritativeRun, applyDelta, applyPersisted, markTerminal,
    beginWaiting, restartLiveReplay, reset,
  }
}
