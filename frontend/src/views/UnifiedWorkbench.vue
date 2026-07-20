<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import ExecutionInspector from '../components/ExecutionInspector.vue'
import StatusBadge from '../components/StatusBadge.vue'
import WorkbenchComposer from '../components/WorkbenchComposer.vue'
import WorkbenchConversationPanel from '../components/WorkbenchConversationPanel.vue'
import WorkbenchTaskSidebar from '../components/WorkbenchTaskSidebar.vue'
import { agentApi } from '../api/agent'
import { workbenchApi } from '../api/workbench'
import { usePresentationStream } from '../composables/usePresentationStream'
import { usePrimaryRunStream } from '../composables/usePrimaryRunStream'
import { useWorkbenchConversation } from '../composables/useWorkbenchConversation'
import { useWorkbenchData } from '../composables/useWorkbenchData'
import { useWorkbenchSelection } from '../composables/useWorkbenchSelection'
import type { WorkItem } from '../types/workbench'

const route = useRoute()
const selection = useWorkbenchSelection()
const data = useWorkbenchData()
const presentationStream = usePresentationStream()
const conversation = useWorkbenchConversation({
  detail: data.detail,
  inputs: data.inputs,
  presentations: presentationStream.publicPresentations,
  approval: data.approval,
})
const primaryStream = usePrimaryRunStream({
  expectedRunId: () => conversation.primaryRunId.value,
  onDelta: event => conversation.applyDelta(event),
  onTerminal: state => {
    conversation.markTerminal(state)
    void refreshAuthoritativeMessages()
  },
  onSourceChanged: event => {
    if (['AGENT_RUN', 'INCIDENT', 'RECOVERY_PLAN'].includes(event.sourceType)) {
      void data.refreshTree(selection.selectedWorkItemId.value)
    }
  },
  onReplayStart: () => conversation.restartLiveReplay(),
})

const content = ref('')
const busy = ref(false)
const controlBusy = ref(false)
const error = ref('')
const reviewer = ref('workbench-reviewer')
const decisionReason = ref('已核对工具参数、影响范围与恢复边界')
const copied = ref(false)
const leftDrawerOpen = ref(false)
const rightDrawerOpen = ref(false)
let timer = 0
let refreshGeneration = 0

const selected = computed(() => selection.history.value.find(item =>
  item.workItemId === selection.selectedWorkItemId.value)
  ?? data.workItems.value.find(item => item.workItemId === selection.selectedWorkItemId.value) ?? null)
const finalAnswerAt = computed(() => conversation.answer.value.state === 'COMPLETED'
  ? conversation.answer.value.createdAt : undefined)

function targetLabel(target: string) {
  return ({ GENERAL_AGENT: 'General', ORDERCARE_CASE: 'OrderCare', INCIDENT_INVESTIGATION: 'Incident',
    INCIDENT_RECOVERY_PLAN: 'Planner' } as Record<string, string>)[target] ?? target ?? 'Routing'
}

function stopWorkItemResources() {
  presentationStream.stop()
  primaryStream.stop()
  conversation.reset()
  data.invalidate()
}

async function loadHistory(current: WorkItem[]) {
  const otherIds = selection.knownConversations()
    .filter(id => id !== selection.conversationId.value).slice(0, 11)
  const lists = await Promise.all(otherIds.map(id => workbenchApi.workItems(id).catch(() => [])))
  selection.replaceHistory([...current, ...lists.flat()])
}

function synchronizeAnswer() {
  const work = data.detail.value?.workItem
  if (!work) return
  const waiting = !['COMPLETED', 'FAILED', 'CANCELLED'].includes(work.executionState.toUpperCase())
  conversation.prepareWork(work.workItemId, work.activeRunId, waiting)
  const persisted = conversation.applyPersisted(data.messages.value, work.activeRunId)
  if (persisted) return
  const terminal = `${work.controlState} ${work.executionState} ${work.outcome}`.toUpperCase()
  if (terminal.includes('CANCELLED')) conversation.markTerminal('CANCELLED')
  else if (terminal.includes('FAILED')) conversation.markTerminal('FAILED')
  else if (terminal.includes('COMPLETED') || terminal.includes('CLOSED') || terminal.includes('RESOLVED')) {
    conversation.markTerminal('COMPLETED')
  }
}

function ensureStreams() {
  const detail = data.detail.value
  if (!detail) return
  const workItemId = detail.workItem.workItemId
  if (presentationStream.activeWorkItemId() !== workItemId) void presentationStream.start(workItemId)
  if (primaryStream.activeWorkItemId() !== workItemId) primaryStream.start(detail)
}

async function refreshAuthoritativeMessages() {
  const workItemId = selection.selectedWorkItemId.value
  const conversationId = selection.conversationId.value
  if (!workItemId || !conversationId) return
  if (await data.refreshMessages(conversationId)) synchronizeAnswer()
}

async function refresh() {
  const token = ++refreshGeneration
  const requestedConversation = selection.conversationId.value
  try {
    if (!await data.loadConversation(requestedConversation)) return
    if (token !== refreshGeneration || requestedConversation !== selection.conversationId.value) return
    await loadHistory(data.workItems.value)
    if (token !== refreshGeneration) return

    const linkedRunId = String(route.query.runId ?? '')
    if (linkedRunId) {
      selection.selectedWorkItemId.value = selection.history.value
        .find(item => item.activeRunId === linkedRunId)?.workItemId ?? selection.selectedWorkItemId.value
    }
    const available = data.workItems.value.some(item => item.workItemId === selection.selectedWorkItemId.value)
    if (!available) {
      selection.selectedWorkItemId.value = data.focus.value?.focusedWorkItemId
        || data.workItems.value[0]?.workItemId || ''
    }
    if (!selection.selectedWorkItemId.value) {
      presentationStream.stop()
      primaryStream.stop()
      conversation.reset()
      return
    }

    const requestedWorkItem = selection.selectedWorkItemId.value
    if (!await data.loadSelected(requestedWorkItem)) return
    if (token !== refreshGeneration || requestedWorkItem !== selection.selectedWorkItemId.value) return
    synchronizeAnswer()
    ensureStreams()
    error.value = ''
  } catch (cause) {
    if (token === refreshGeneration) error.value = cause instanceof Error ? cause.message : '工作台加载失败'
  }
}

async function newTask() {
  stopWorkItemResources()
  selection.beginNewConversation()
  data.clear()
  content.value = ''
  leftDrawerOpen.value = false
  await refresh()
}

async function choose(item: WorkItem) {
  if (item.workItemId === selection.selectedWorkItemId.value) return
  stopWorkItemResources()
  selection.select(item)
  leftDrawerOpen.value = false
  await refresh()
}

async function submit() {
  if (!content.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  conversation.beginWaiting()
  try {
    const result = await workbenchApi.submit(selection.conversationId.value, content.value.trim())
    content.value = ''
    if (result.workItemId && result.workItemId !== selection.selectedWorkItemId.value) {
      stopWorkItemResources()
      selection.selectedWorkItemId.value = result.workItemId
    }
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提交失败' }
  finally { busy.value = false }
}

async function makeFocus() {
  if (!selected.value || !data.focus.value) return
  await workbenchApi.switchFocus(selection.conversationId.value, selected.value.workItemId, data.focus.value.version)
  await refresh()
}

async function decidePreview(approved: boolean) {
  if (!data.detail.value?.preview || !selected.value || busy.value) return
  busy.value = true
  try {
    approved
      ? await workbenchApi.confirm(selected.value.workItemId, data.detail.value.preview)
      : await workbenchApi.reject(selected.value.workItemId, data.detail.value.preview.previewId)
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '确认失败' }
  finally { busy.value = false }
}

async function decideApproval(approved: boolean) {
  if (!data.approval.value || !selected.value || controlBusy.value) return
  controlBusy.value = true
  try {
    await agentApi.decideApproval(data.approval.value.approvalId, approved,
      reviewer.value.trim() || 'workbench-reviewer', decisionReason.value.trim())
    await workbenchApi.command(selected.value.workItemId, 'resume', selected.value.version)
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '审批操作失败' }
  finally { controlBusy.value = false }
}

async function executeCommand(command: 'pause' | 'resume' | 'cancel') {
  if (!selected.value || controlBusy.value) return
  controlBusy.value = true
  try { await workbenchApi.command(selected.value.workItemId, command, selected.value.version); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : `${command} 操作失败` }
  finally { controlBusy.value = false }
}

async function copyAnswer(value: string) {
  await navigator.clipboard.writeText(value)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1400)
}

watch(() => presentationStream.publicPresentations.value.length, () => {
  const latest = presentationStream.publicPresentations.value.at(-1)
  if (latest?.kind === 'FINAL_RESULT') {
    conversation.markTerminal('COMPLETED')
    void refreshAuthoritativeMessages()
  }
})

onMounted(() => { void refresh(); timer = window.setInterval(refresh, 5000) })
onBeforeUnmount(() => {
  window.clearInterval(timer)
  presentationStream.stop()
  primaryStream.stop()
  data.invalidate()
})
</script>

<template>
  <div class="task-workbench-shell" :class="{ 'left-open': leftDrawerOpen, 'right-open': rightDrawerOpen }">
    <div v-if="leftDrawerOpen || rightDrawerOpen" class="workbench-scrim" @click="leftDrawerOpen = false; rightDrawerOpen = false" />

    <WorkbenchTaskSidebar :items="selection.filteredHistory.value" :selected-id="selection.selectedWorkItemId.value" :search="selection.search.value" @update:search="selection.search.value = $event" @new-task="newTask" @select="choose" @close="leftDrawerOpen = false" />

    <main class="task-conversation-column">
      <header class="task-header">
        <button class="task-mobile-toggle" type="button" title="打开任务列表" @click="leftDrawerOpen = true">☰</button>
        <div><h1>{{ selected?.originalGoal || '新任务' }}</h1><span v-if="selected">{{ targetLabel(selected.activeExecutionTarget) }} · {{ selected.controlState }}</span><span v-else>描述目标，系统会选择合适的执行方式</span></div>
        <div class="task-header-actions"><span v-if="copied" class="copy-confirmation">已复制</span><StatusBadge v-if="selected" :value="selected.executionState" compact /><button v-if="data.focus.value && selected && data.focus.value.focusedWorkItemId !== selected.workItemId" type="button" title="设为当前任务" @click="makeFocus">聚焦</button><button class="inspector-mobile-toggle" type="button" title="打开执行检查器" @click="rightDrawerOpen = true">◎</button></div>
      </header>

      <WorkbenchConversationPanel :entries="conversation.entries.value" :has-work="Boolean(data.detail.value)" :busy="busy || controlBusy" :reviewer="reviewer" :decision-reason="decisionReason" @update:reviewer="reviewer = $event" @update:decision-reason="decisionReason = $event" @confirm-preview="decidePreview" @decide-approval="decideApproval" @copy="copyAnswer" />
      <WorkbenchComposer v-model="content" :busy="busy" :error="error" @submit="submit" />
    </main>

    <ExecutionInspector v-if="data.detail.value" :detail="data.detail.value" :tree="data.tree.value" :events="primaryStream.rawEvents.value" :budget="data.budget.value" :approval="data.approval.value" :inspector-presentations="presentationStream.inspectorPresentations.value" :delta-stream-state="primaryStream.connectionState.value" :presentation-stream-state="presentationStream.connectionState.value" :work-cursor="primaryStream.workCursor.value" :run-cursor="primaryStream.runCursor.value" :presentation-cursor="presentationStream.presentationCursor.value" :last-event-at="primaryStream.lastEventAt.value || presentationStream.lastEventAt.value" :gap="primaryStream.gap.value || presentationStream.gap.value" :sync-error="primaryStream.syncError.value || presentationStream.syncError.value" :final-answer-at="finalAnswerAt" :busy="controlBusy" @command="executeCommand" />
    <aside v-else class="execution-inspector inspector-placeholder"><span>◎</span><strong>执行检查器</strong><p>任务开始后，这里会显示状态、活动、Agents 与证据。</p></aside>
  </div>
</template>
