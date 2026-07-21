<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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
import { useWorkbenchTurnHistory } from '../composables/useWorkbenchTurnHistory'
import { useTurnSelection } from '../composables/useTurnSelection'
import { incidentAssessmentMarkdown } from '../utils/incidentAssessment'
import { projectTurnConversationItems } from '../utils/conversationItems'
import type { PresentationLocator } from '../types/conversation'
import type { ComposerAttachment, ConversationHistoryItem, WorkItem } from '../types/workbench'

const route = useRoute()
const selection = useWorkbenchSelection()
const data = useWorkbenchData()
const turnHistory = useWorkbenchTurnHistory()
const turnSelection = useTurnSelection()
const presentationStream = usePresentationStream()
const conversation = useWorkbenchConversation({
  detail: data.detail,
  inputs: data.inputs,
  presentations: presentationStream.publicPresentations,
  approval: data.approval,
  workItems: data.workItems,
  messages: data.messages,
})
const primaryStream = usePrimaryRunStream({
  expectedRunId: () => conversation.primaryRunId.value,
  onDelta: event => conversation.applyDelta(event),
  onTerminal: (state, event) => { void handleTerminal(state, event.sourceId) },
  onSourceChanged: event => {
    if (['AGENT_RUN', 'INCIDENT', 'RECOVERY_PLAN'].includes(event.sourceType)) {
      void data.refreshTree(selection.selectedWorkItemId.value)
    }
  },
  onReplayStart: () => conversation.restartLiveReplay(),
})

const content = ref('')
const attachments = ref<ComposerAttachment[]>([])
const busy = ref(false)
const launchPending = ref(false)
const terminationRequested = ref(false)
const terminationBusy = ref(false)
const controlBusy = ref(false)
const error = ref('')
const reviewer = ref('workbench-reviewer')
const decisionReason = ref('已核对工具参数、影响范围与恢复边界')
const copied = ref(false)
const leftDrawerOpen = ref(false)
const rightDrawerOpen = ref(false)
const shell = ref<HTMLElement | null>(null)
const inspector = ref<InstanceType<typeof ExecutionInspector> | null>(null)
const composer = ref<InstanceType<typeof WorkbenchComposer> | null>(null)
let drawerReturnFocus: HTMLElement | null = null
let timer = 0
let refreshGeneration = 0
let terminalRefreshGeneration = 0

const selected = computed(() => selection.history.value.find(item =>
  item.workItemId === selection.selectedWorkItemId.value)
  ?? data.workItems.value.find(item => item.workItemId === selection.selectedWorkItemId.value) ?? null)
const turnViews = computed(() => turnHistory.orderedSnapshots.value.map(snapshot => {
  const active = snapshot.turn.turnId === data.detail.value?.workItem.sourceInputId
  const answer = active ? conversation.answer.value : snapshot.answer
  const presentations = active ? presentationStream.publicPresentations.value : snapshot.publicPresentations
  const tree = active ? data.tree.value : snapshot.tree
  const entries = projectTurnConversationItems({
    turn: snapshot.turn, detail: active && data.detail.value ? data.detail.value : snapshot.detail,
    inputs: data.inputs.value, presentations,
    approval: active ? data.approval.value : snapshot.approval, answer,
    messages: data.messages.value, tree,
  })
  const start = new Date(snapshot.turn.createdAt).getTime()
  const end = new Date(snapshot.turn.completedAt || snapshot.detail.workItem.updatedAt).getTime()
  return { turn: snapshot.turn, entries,
    stepCount: entries.find(item => item.type === 'EXECUTION_NARRATIVE')?.narrative?.items.length ?? 0,
    agentCount: (tree?.agents.length ?? 0) + (tree?.coordinator ? 1 : 0),
    durationMs: Math.max(0, end - start) }
}))
const finalAnswerAt = computed(() => conversation.answer.value.state === 'COMPLETED'
  ? conversation.answer.value.createdAt : undefined)
const waitingForInput = computed(() => data.detail.value?.workItem.controlState === 'WAITING_INPUT')
const composerControlMode = computed<'idle' | 'running' | 'pausing' | 'paused' | 'cancelling' | 'waiting'>(() => {
  if (terminationRequested.value) return 'cancelling'
  if (launchPending.value) return 'running'
  const work = data.detail.value?.workItem
  if (!work) return 'idle'
  if (work.controlState === 'PAUSE_REQUESTED') return 'pausing'
  if (work.controlState === 'CANCEL_REQUESTED') return 'cancelling'
  if (work.controlState === 'PAUSED' || work.executionState === 'PAUSED') return 'paused'
  if (['STARTING', 'RUNNING'].includes(work.executionState)
      || ['DISPATCHING', 'DISPATCHED'].includes(work.controlState)) return 'running'
  if (['WAITING_INPUT', 'WAITING_CONFIRMATION', 'MANUAL_REVIEW'].includes(work.controlState)
      || work.executionState === 'WAITING_APPROVAL') return 'waiting'
  return 'idle'
})
const stopAvailable = computed(() => {
  if (launchPending.value || terminationRequested.value) return true
  const work = data.detail.value?.workItem
  if (!work) return false
  return !['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(work.executionState)
    && !['CLOSED', 'ABANDONED', 'WAITING_INPUT', 'WAITING_CONFIRMATION', 'MANUAL_REVIEW'].includes(work.controlState)
})

function targetLabel(target: string) {
  return ({ GENERAL_AGENT: 'General', ORDERCARE_CASE: 'OrderCare', INCIDENT_INVESTIGATION: 'Incident',
    INCIDENT_RECOVERY_PLAN: 'Planner' } as Record<string, string>)[target] ?? target ?? 'Routing'
}

function stopWorkItemResources() {
  terminalRefreshGeneration += 1
  presentationStream.stop()
  primaryStream.stop()
  conversation.reset()
  data.invalidate()
}

function clearConversationResources() {
  stopWorkItemResources()
  turnHistory.clear()
  turnSelection.reset()
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
  const waiting = Boolean(work.activeRunId)
    && !['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(work.executionState.toUpperCase())
  conversation.prepareWork(work.workItemId, work.activeRunId, waiting)
  const persisted = conversation.applyPersisted(data.messages.value, work.activeRunId)
  if (persisted) return
  if (work.activeExecutionTarget === 'INCIDENT_INVESTIGATION'
      && work.outcome.toUpperCase() === 'ASSESSED') {
    const assessment = incidentAssessmentMarkdown(data.tree.value)
    if (conversation.applyProjectedResult(assessment, work.updatedAt)) return
  }
  const terminal = `${work.controlState} ${work.executionState} ${work.outcome}`.toUpperCase()
  if (terminal.includes('CANCELLED')) conversation.markTerminal('CANCELLED')
  else if (terminal.includes('FAILED')) conversation.markTerminal('FAILED')
  else if (terminal.includes('COMPLETED') || terminal.includes('CLOSED') || terminal.includes('RESOLVED')) {
    conversation.markTerminal('COMPLETED')
  }
}

function synchronizeActiveTurn() {
  const detail = data.detail.value
  if (!detail) return
  turnHistory.mergeActive(detail.workItem.sourceInputId, detail,
    presentationStream.publicPresentations.value,
    presentationStream.inspectorPresentations.value,
    primaryStream.rawEvents.value,
    data.tree.value, data.budget.value, data.approval.value,
    conversation.answer.value)
}

function ensureStreams() {
  const detail = data.detail.value
  if (!detail) return
  const workItemId = detail.workItem.workItemId
  if (presentationStream.activeWorkItemId() !== workItemId) void presentationStream.start(workItemId)
  if (primaryStream.activeWorkItemId() !== workItemId) primaryStream.start(detail)
}

function isTerminalExecutionState(state: string) {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(state.toUpperCase())
}

async function handleTerminal(state: 'COMPLETED' | 'FAILED' | 'CANCELLED', sourceRunId: string) {
  const workItemId = selection.selectedWorkItemId.value
  const conversationId = selection.conversationId.value
  if (!workItemId || !conversationId) return
  const expectedRunId = conversation.primaryRunId.value || data.detail.value?.workItem.activeRunId || ''
  if (sourceRunId && expectedRunId && sourceRunId !== expectedRunId) return
  conversation.markTerminal(state)
  const token = ++terminalRefreshGeneration
  for (const delay of [0, 100, 250, 500, 1000]) {
    if (delay) await new Promise(resolve => window.setTimeout(resolve, delay))
    if (token !== terminalRefreshGeneration || workItemId !== selection.selectedWorkItemId.value) return
    if (!await data.loadConversation(conversationId)) return
    if (token !== terminalRefreshGeneration || workItemId !== selection.selectedWorkItemId.value) return
    await loadHistory(data.workItems.value)
    if (!await data.loadSelected(workItemId)) return
    if (token !== terminalRefreshGeneration || workItemId !== selection.selectedWorkItemId.value) return
    const authoritative = data.detail.value?.workItem
    if (!authoritative) return
    synchronizeAnswer()
    ensureStreams()
    if (authoritative.activeRunId !== sourceRunId || isTerminalExecutionState(authoritative.executionState)) return
  }
}

async function refresh() {
  const token = ++refreshGeneration
  const requestedConversation = selection.conversationId.value
  try {
    if (!await data.loadConversation(requestedConversation)) return
    if (token !== refreshGeneration || requestedConversation !== selection.conversationId.value) return
    await loadHistory(data.workItems.value)
    if (token !== refreshGeneration) return
    await turnHistory.hydrate(requestedConversation, data.inputs.value, data.workItems.value,
      data.messages.value, selection.selectedWorkItemId.value)
    if (token !== refreshGeneration) return
    turnSelection.synchronize(turnHistory.turns.value)

    const linkedRunId = String(route.query.runId ?? '')
    if (linkedRunId) {
      selection.selectedWorkItemId.value = selection.history.value
        .find(item => item.activeRunId === linkedRunId)?.workItemId ?? selection.selectedWorkItemId.value
      const linkedTurn = turnHistory.turns.value.find(item => item.workItemId === selection.selectedWorkItemId.value)
      if (linkedTurn) turnSelection.select(linkedTurn.turnId)
    }
    const selectedTurn = turnHistory.turns.value.find(item => item.turnId === turnSelection.selectedTurnId.value)
    if (selectedTurn) selection.selectedWorkItemId.value = selectedTurn.workItemId
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
    synchronizeActiveTurn()
    error.value = ''
  } catch (cause) {
    if (token === refreshGeneration) error.value = cause instanceof Error ? cause.message : '工作台加载失败'
  }
}

async function newTask() {
  clearConversationResources()
  selection.beginNewConversation()
  data.clear()
  content.value = ''
  attachments.value = []
  terminationRequested.value = false
  terminationBusy.value = false
  leftDrawerOpen.value = false
  await refresh()
}

async function choose(item: ConversationHistoryItem) {
  if (item.conversationId === selection.conversationId.value) return
  clearConversationResources()
  selection.select(item)
  leftDrawerOpen.value = false
  await refresh()
}

async function submit() {
  if (!content.value.trim() || busy.value) return
  busy.value = true
  launchPending.value = true
  terminationRequested.value = false
  error.value = ''
  conversation.beginWaiting()
  turnSelection.follow(turnHistory.turns.value)
  try {
    const submittedContent = serializeSubmission(content.value.trim(), attachments.value)
    const result = await workbenchApi.submit(selection.conversationId.value, submittedContent)
    content.value = ''
    attachments.value = []
    if (result.workItemId && result.workItemId !== selection.selectedWorkItemId.value) {
      stopWorkItemResources()
      selection.selectedWorkItemId.value = result.workItemId
    }
    if (terminationRequested.value && result.workItemId) await terminateWhenRunnable(result.workItemId)
    else await refresh()
  } catch (cause) {
    terminationRequested.value = false
    error.value = cause instanceof Error ? cause.message : '提交失败'
  }
  finally { launchPending.value = false; busy.value = false }
}

async function selectTurn(turnId: string, lock = true) {
  const turn = turnHistory.turns.value.find(item => item.turnId === turnId)
  if (!turn || turn.workItemId === selection.selectedWorkItemId.value) {
    if (lock) turnSelection.select(turnId)
    return
  }
  if (lock) turnSelection.select(turnId)
  else turnSelection.selectedTurnId.value = turnId
  stopWorkItemResources()
  selection.selectedWorkItemId.value = turn.workItemId
  if (!await data.loadSelected(turn.workItemId)) return
  synchronizeAnswer()
  ensureStreams()
  synchronizeActiveTurn()
}

async function followCurrentTurn() {
  turnSelection.follow(turnHistory.turns.value)
  const turn = turnHistory.turns.value.find(item => item.turnId === turnSelection.selectedTurnId.value)
  if (turn) await selectTurn(turn.turnId, false)
}

async function locatePresentations(locator: PresentationLocator) {
  if (locator.turnId && locator.turnId !== turnSelection.selectedTurnId.value) {
    await selectTurn(locator.turnId)
  }
  rightDrawerOpen.value = true
  synchronizeActiveTurn()
  await nextTick()
  inspector.value?.locatePresentations(locator.presentationIds)
}

async function terminateTask() {
  if (terminationBusy.value) return
  terminationRequested.value = true
  error.value = ''
  if (launchPending.value) return
  const workItemId = selection.selectedWorkItemId.value
  if (workItemId) await terminateWhenRunnable(workItemId)
}

async function terminateWhenRunnable(workItemId: string) {
  if (terminationBusy.value) return
  terminationBusy.value = true
  try {
    for (let attempt = 0; attempt < 300; attempt += 1) {
      if (!terminationRequested.value || workItemId !== selection.selectedWorkItemId.value) return
      const detail = await workbenchApi.detail(workItemId)
      const work = detail.workItem
      if (['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(work.executionState)
          || ['CLOSED', 'ABANDONED'].includes(work.controlState)) {
        terminationRequested.value = false
        await refresh()
        return
      }
      if (work.executionState === 'NOT_STARTED'
          && !['DISPATCHING', 'DISPATCHED'].includes(work.controlState)) {
        await workbenchApi.command(workItemId, 'abandon', work.version)
        terminationRequested.value = false
        await refresh()
        return
      }
      const runtimeTarget = ['GENERAL_AGENT', 'ORDERCARE_CASE'].includes(work.activeExecutionTarget)
      const cancellableRuntime = Boolean(work.activeRunId)
        || (runtimeTarget && Boolean(work.dispatchRequestId) && work.executionState === 'STARTING')
      if (cancellableRuntime && ['STARTING', 'RUNNING', 'PAUSED', 'WAITING_APPROVAL'].includes(work.executionState)) {
        await workbenchApi.command(workItemId, 'cancel', work.version)
        terminationRequested.value = false
        await refresh()
        return
      }
      await new Promise(resolve => window.setTimeout(resolve, 100))
    }
    throw new Error('任务仍在创建执行上下文，请稍后再次终止。')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '终止任务失败'
  } finally {
    terminationBusy.value = false
  }
}

function serializeSubmission(goal: string, files: ComposerAttachment[]) {
  if (!files.length) return goal
  const appendix = files.map(file => [
    `<attachment name="${file.name.replace(/["<>]/g, '_')}" media-type="${file.mediaType}" size="${file.size}">`,
    file.content,
    '</attachment>',
  ].join('\n')).join('\n\n')
  return `${goal}\n\n<workbench_attachments>\n${appendix}\n</workbench_attachments>`
}

async function retryTask() {
  if (!selected.value || busy.value) return
  content.value = selected.value.originalGoal
  await submit()
}

async function showDiagnostics() {
  rightDrawerOpen.value = true
  await nextTick()
  inspector.value?.showDiagnostics()
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

async function copyAnswer(value: string) {
  await navigator.clipboard.writeText(value)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1400)
}

async function focusClarificationInput() {
  await nextTick()
  composer.value?.focus()
}

async function openDrawer(side: 'left' | 'right') {
  drawerReturnFocus = document.activeElement as HTMLElement | null
  if (side === 'left') leftDrawerOpen.value = true
  else rightDrawerOpen.value = true
  await nextTick()
  const selector = side === 'left' ? '.task-sidebar' : '.execution-inspector'
  shell.value?.querySelector<HTMLElement>(`${selector} button, ${selector} input`)?.focus()
}

function closeDrawers() {
  leftDrawerOpen.value = false
  rightDrawerOpen.value = false
  drawerReturnFocus?.focus()
  drawerReturnFocus = null
}

function trapDrawerFocus(event: KeyboardEvent) {
  if (!leftDrawerOpen.value && !rightDrawerOpen.value) return
  if (event.key === 'Escape') { closeDrawers(); return }
  if (event.key !== 'Tab') return
  const selector = leftDrawerOpen.value ? '.task-sidebar' : '.execution-inspector'
  const focusable = [...(shell.value?.querySelectorAll<HTMLElement>(`${selector} button:not(:disabled), ${selector} input, ${selector} textarea, ${selector} [href], ${selector} summary`) ?? [])]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable.at(-1)!
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

watch(() => presentationStream.publicPresentations.value.length, () => {
  const latest = presentationStream.publicPresentations.value.at(-1)
  if (latest?.kind === 'FINAL_RESULT') {
    void handleTerminal('COMPLETED', latest.sourceId)
  } else if (latest?.kind === 'ERROR' && latest.sourceType === 'AGENT_RUN') {
    void handleTerminal('FAILED', latest.sourceId)
  }
})

watch(() => [presentationStream.publicPresentations.value.length,
  presentationStream.inspectorPresentations.value.length,
  primaryStream.rawEvents.value.length,
  conversation.answer.value.content.length,
  conversation.answer.value.state], synchronizeActiveTurn)

onMounted(() => { void refresh(); timer = window.setInterval(refresh, 5000); window.addEventListener('keydown', trapDrawerFocus) })
onBeforeUnmount(() => {
  window.clearInterval(timer)
  presentationStream.stop()
  primaryStream.stop()
  data.invalidate()
  window.removeEventListener('keydown', trapDrawerFocus)
})
</script>

<template>
  <div ref="shell" class="task-workbench-shell" :class="{ 'left-open': leftDrawerOpen, 'right-open': rightDrawerOpen }">
    <div v-if="leftDrawerOpen || rightDrawerOpen" class="workbench-scrim" @click="closeDrawers" />

      <WorkbenchTaskSidebar :items="selection.filteredHistory.value" :selected-id="selection.conversationId.value" :search="selection.search.value" @update:search="selection.search.value = $event" @new-task="newTask" @select="choose" @close="closeDrawers" />

    <main class="task-conversation-column">
      <header class="task-header">
        <button class="task-mobile-toggle" type="button" aria-label="打开任务列表" @click="openDrawer('left')">☰</button>
        <div><h1>{{ selected?.originalGoal || '新任务' }}</h1><span v-if="selected">{{ targetLabel(selected.activeExecutionTarget) }} · {{ selected.controlState }}</span><span v-else>描述目标，系统会选择合适的执行方式</span></div>
        <div class="task-header-actions"><span v-if="copied" class="copy-confirmation">已复制</span><StatusBadge v-if="selected" :value="selected.executionState" compact /><button v-if="data.focus.value && selected && data.focus.value.focusedWorkItemId !== selected.workItemId" type="button" aria-label="设为当前任务" @click="makeFocus">聚焦</button><button class="inspector-mobile-toggle" type="button" aria-label="打开执行检查器" @click="openDrawer('right')">◎</button></div>
      </header>

      <WorkbenchConversationPanel :turns="turnViews" :selected-turn-id="turnSelection.selectedTurnId.value" :has-work="Boolean(data.workItems.value.length)" :busy="busy || controlBusy" :reviewer="reviewer" :decision-reason="decisionReason" @select-turn="selectTurn" @update:reviewer="reviewer = $event" @update:decision-reason="decisionReason = $event" @confirm-preview="decidePreview" @decide-approval="decideApproval" @copy="copyAnswer" @retry="retryTask" @diagnostics="showDiagnostics" @supply-input="focusClarificationInput" @locate-presentations="locatePresentations" />
      <WorkbenchComposer ref="composer" v-model="content" v-model:attachments="attachments" :busy="busy" :stop-available="stopAvailable" :stopping="terminationBusy" :error="error" :waiting-for-input="waitingForInput" :control-mode="composerControlMode" @submit="submit" @stop="terminateTask" />
    </main>

    <ExecutionInspector v-if="data.detail.value" ref="inspector" :detail="data.detail.value" :tree="data.tree.value" :events="primaryStream.rawEvents.value" :budget="data.budget.value" :approval="data.approval.value" :inspector-presentations="presentationStream.inspectorPresentations.value" :turn-snapshots="turnHistory.orderedSnapshots.value" :selected-turn-id="turnSelection.selectedTurnId.value" :follow-current="turnSelection.followCurrent.value" :delta-stream-state="primaryStream.connectionState.value" :presentation-stream-state="presentationStream.connectionState.value" :work-cursor="primaryStream.workCursor.value" :run-cursor="primaryStream.runCursor.value" :presentation-cursor="presentationStream.presentationCursor.value" :reconnect-count="primaryStream.reconnectCount.value + presentationStream.reconnectCount.value" :last-event-at="primaryStream.lastEventAt.value || presentationStream.lastEventAt.value" :gap="primaryStream.gap.value || presentationStream.gap.value" :sync-error="primaryStream.syncError.value || presentationStream.syncError.value" :final-answer-at="finalAnswerAt" @select-turn="selectTurn" @follow-current="followCurrentTurn" />
    <aside v-else class="execution-inspector inspector-placeholder"><span>◎</span><strong>执行检查器</strong><p>任务开始后，这里会显示状态、活动、Agents 与证据。</p></aside>
  </div>
</template>
