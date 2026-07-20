<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import ConversationItemRenderer from '../components/ConversationItemRenderer.vue'
import ExecutionInspector from '../components/ExecutionInspector.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { agentApi } from '../api/agent'
import { workbenchApi } from '../api/workbench'
import { projectConversationItems } from '../utils/conversationItems'
import type { AgentConversationMessage, ApprovalRecord } from '../types/agent'
import type { PublicPresentation, WorkEvent, WorkExecutionTree, WorkFocus, WorkInput, WorkItem, WorkItemBudget, WorkItemDetail, WorkStreamItem } from '../types/workbench'

const CONVERSATION_KEY = 'unified-workbench-conversation'
const HISTORY_KEY = 'unified-workbench-conversations'

function newConversationId() {
  const suffix = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `${Date.now()}`
  return `workbench-${suffix}`
}

function knownConversations() {
  try {
    const parsed = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]')
    return Array.isArray(parsed) ? parsed.filter(value => typeof value === 'string').slice(0, 20) : []
  } catch { return [] }
}

function rememberConversation(value: string) {
  const next = [value, ...knownConversations().filter(item => item !== value)].slice(0, 20)
  localStorage.setItem(HISTORY_KEY, JSON.stringify(next))
}

const route = useRoute()
const conversationId = ref(localStorage.getItem(CONVERSATION_KEY) || newConversationId())
rememberConversation(conversationId.value)

const content = ref('')
const search = ref('')
const inputs = ref<WorkInput[]>([])
const conversationMessages = ref<AgentConversationMessage[]>([])
const workItems = ref<WorkItem[]>([])
const historyWorkItems = ref<WorkItem[]>([])
const focus = ref<WorkFocus | null>(null)
const selectedId = ref('')
const detail = ref<WorkItemDetail | null>(null)
const executionTree = ref<WorkExecutionTree | null>(null)
const budget = ref<WorkItemBudget | null>(null)
const pendingApproval = ref<ApprovalRecord | null>(null)
const streamEvents = ref<WorkEvent[]>([])
const presentations = ref<PublicPresentation[]>([])
const liveAnswer = ref('')
const streamState = ref<'idle' | 'connecting' | 'live' | 'recovering' | 'error'>('idle')
const busy = ref(false)
const controlBusy = ref(false)
const error = ref('')
const reviewer = ref('workbench-reviewer')
const decisionReason = ref('已核对工具参数、影响范围与恢复边界')
const copied = ref(false)
const followOutput = ref(true)
const leftDrawerOpen = ref(false)
const rightDrawerOpen = ref(false)
const conversationFeed = ref<HTMLElement | null>(null)

let timer = 0
let reconnectTimer = 0
let eventSource: EventSource | null = null
let streamGeneration = 0
let refreshGeneration = 0
let workCursor = -1
let runCursor = -1
const seenWorkEvents = new Set<string>()
const seenRunEvents = new Set<string>()

const selected = computed(() => historyWorkItems.value.find(item => item.workItemId === selectedId.value)
  ?? workItems.value.find(item => item.workItemId === selectedId.value) ?? null)
const filteredHistory = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return historyWorkItems.value.filter(item => !keyword || [item.originalGoal, item.activeExecutionTarget, item.controlState]
    .some(value => String(value ?? '').toLowerCase().includes(keyword)))
})
const timeline = computed(() => detail.value ? projectConversationItems({
  detail: detail.value,
  inputs: inputs.value,
  messages: conversationMessages.value,
  presentations: presentations.value,
  tree: executionTree.value,
  approval: pendingApproval.value,
  liveAnswer: liveAnswer.value,
}) : [])
const finalAnswerAt = computed(() => timeline.value.find(item => item.type === 'FINAL_ANSWER' && !item.live)?.createdAt)

function stateTone(item: WorkItem) {
  const value = `${item.controlState} ${item.executionState} ${item.outcome}`.toUpperCase()
  if (/(FAILED|CANCELLED|REJECTED|MANUAL_REVIEW|ABANDONED)/.test(value)) return 'failed'
  if (/(COMPLETED|CLOSED|RESOLVED)/.test(value)) return 'completed'
  if (/(WAITING|PAUSED)/.test(value)) return 'waiting'
  return 'active'
}

function targetLabel(target: string) {
  return ({ GENERAL_AGENT: 'General', ORDERCARE_CASE: 'OrderCare', INCIDENT_INVESTIGATION: 'Incident', INCIDENT_RECOVERY_PLAN: 'Planner' } as Record<string, string>)[target] ?? target ?? 'Routing'
}

function relativeTime(value: string) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 60) return '刚刚'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(value))
}

async function scrollToBottom(force = false) {
  await nextTick()
  if (!force && !followOutput.value) return
  if (conversationFeed.value) conversationFeed.value.scrollTop = conversationFeed.value.scrollHeight
}

function handleConversationScroll() {
  const element = conversationFeed.value
  if (!element) return
  followOutput.value = element.scrollHeight - element.scrollTop - element.clientHeight < 96
}

function resumeFollowingOutput() {
  followOutput.value = true
  void scrollToBottom(true)
}

function closeStream() {
  streamGeneration += 1
  eventSource?.close()
  eventSource = null
  window.clearTimeout(reconnectTimer)
}

function applyResumeToken(token: string) {
  const match = /^w:(-?\d+);r:(-?\d+)$/.exec(token)
  if (!match) return
  workCursor = Math.max(workCursor, Number(match[1]))
  runCursor = Math.max(runCursor, Number(match[2]))
}

function parseStreamEvent(event: MessageEvent<string>): WorkStreamItem | null {
  try { return JSON.parse(event.data) as WorkStreamItem }
  catch { error.value = '事件流返回了无法解析的数据'; return null }
}

async function loadAllEvents(workItemId: string) {
  const result: WorkEvent[] = []
  let cursor = -1
  for (;;) {
    const page = await workbenchApi.events(workItemId, cursor, 500)
    if (!page.length) break
    result.push(...page)
    cursor = page.at(-1)?.sequence ?? cursor
    if (page.length < 500) break
  }
  return result
}

async function loadAllPresentations(workItemId: string) {
  const result: PublicPresentation[] = []
  let cursor = -1
  for (;;) {
    const page = await workbenchApi.presentations(workItemId, cursor, 500)
    if (!page.length) break
    result.push(...page)
    cursor = page.at(-1)?.sequence ?? cursor
    if (page.length < 500) break
  }
  return result
}

async function refreshPresentations(workItemId: string) {
  try {
    const next = await loadAllPresentations(workItemId)
    if (selectedId.value === workItemId) presentations.value = next
  } catch { /* Presentation projection may briefly lag the raw WorkEvent stream. */ }
}

async function recoverGap(workItemId: string) {
  closeStream()
  streamState.value = 'recovering'
  try {
    const historical = await loadAllEvents(workItemId)
    const continuous = historical.every((event, index) => index === 0 || event.sequence === historical[index - 1].sequence + 1)
    if (!continuous) throw new Error('执行事件存在持久化序号缺口')
    streamEvents.value = historical
    seenWorkEvents.clear()
    historical.forEach(event => seenWorkEvents.add(event.eventId))
    workCursor = historical.at(-1)?.sequence ?? -1
    runCursor = -1
    liveAnswer.value = ''
    seenRunEvents.clear()
    connectStream(workItemId)
  } catch (cause) {
    streamState.value = 'error'
    error.value = cause instanceof Error ? cause.message : '执行时间线恢复失败'
  }
}

function connectStream(workItemId: string) {
  closeStream()
  const generation = streamGeneration
  streamState.value = 'connecting'
  const source = new EventSource(workbenchApi.streamUrl(workItemId, workCursor, runCursor))
  eventSource = source
  source.onopen = () => { if (generation === streamGeneration) streamState.value = 'live' }
  source.addEventListener('work-event', raw => {
    if (generation !== streamGeneration) return
    const event = parseStreamEvent(raw as MessageEvent<string>)
    if (!event) return
    if (event.workSequence > workCursor + 1) { void recoverGap(workItemId); return }
    applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
    if (!event.eventId || seenWorkEvents.has(event.eventId)) return
    seenWorkEvents.add(event.eventId)
    streamEvents.value.push({
      eventId: event.eventId, sequence: event.workSequence, eventType: event.eventType,
      phase: String(event.payload.runtimeEventType ?? event.payload.incidentEventType
        ?? event.payload.recoveryPlanEventType ?? event.payload.phase ?? event.eventType), summary: event.content,
      projectedAt: event.createdAt, sourceType: event.sourceType, sourceId: event.sourceId,
      sourceSequence: event.sourceSequence, sourceCreatedAt: event.createdAt, payload: event.payload,
    })
    void refreshPresentations(workItemId)
    if (['AGENT_RUN', 'INCIDENT', 'RECOVERY_PLAN'].includes(event.sourceType)) void refreshSelectedProjection(workItemId)
  })
  source.addEventListener('model-delta', raw => {
    if (generation !== streamGeneration) return
    const event = parseStreamEvent(raw as MessageEvent<string>)
    if (!event) return
    applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
    if (!event.eventId || seenRunEvents.has(event.eventId)) return
    seenRunEvents.add(event.eventId)
    liveAnswer.value += event.content
  })
  source.addEventListener('heartbeat', raw => {
    if (generation !== streamGeneration) return
    const event = parseStreamEvent(raw as MessageEvent<string>)
    if (event) applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
  })
  source.addEventListener('gap', () => { if (generation === streamGeneration) void recoverGap(workItemId) })
  source.addEventListener('sync-error', () => { if (generation === streamGeneration) streamState.value = 'error' })
  source.onerror = () => {
    if (generation !== streamGeneration) return
    source.close()
    streamState.value = 'connecting'
    reconnectTimer = window.setTimeout(() => connectStream(workItemId), 1200)
  }
}

function resetStream(next: WorkItemDetail) {
  closeStream()
  streamEvents.value = [...next.events].sort((left, right) => left.sequence - right.sequence)
  seenWorkEvents.clear()
  streamEvents.value.forEach(event => seenWorkEvents.add(event.eventId))
  seenRunEvents.clear()
  workCursor = streamEvents.value.at(-1)?.sequence ?? -1
  runCursor = -1
  liveAnswer.value = ''
  connectStream(next.workItem.workItemId)
}

async function loadHistory(currentItems: WorkItem[]) {
  const ids = knownConversations().filter(id => id !== conversationId.value).slice(0, 11)
  const lists = await Promise.all(ids.map(id => workbenchApi.workItems(id).catch(() => [])))
  const unique = new Map<string, WorkItem>()
  ;[...currentItems, ...lists.flat()].forEach(item => unique.set(item.workItemId, item))
  historyWorkItems.value = [...unique.values()].sort((left, right) =>
    new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime())
}

async function refreshSelectedProjection(workItemId: string) {
  if (selectedId.value !== workItemId) return
  try {
    const tree = await workbenchApi.executionTree(workItemId)
    if (selectedId.value === workItemId) executionTree.value = tree
  } catch { /* Tree can be temporarily unavailable while dispatch is being linked. */ }
}

async function refresh() {
  const generation = ++refreshGeneration
  const requestedConversation = conversationId.value
  try {
    const [nextInputs, nextItems, nextMessages] = await Promise.all([
      workbenchApi.inputs(requestedConversation), workbenchApi.workItems(requestedConversation),
      agentApi.conversationMessages(requestedConversation, 500),
    ])
    if (generation !== refreshGeneration || requestedConversation !== conversationId.value) return
    inputs.value = nextInputs
    workItems.value = nextItems
    conversationMessages.value = nextMessages
    await loadHistory(nextItems)
    if (generation !== refreshGeneration || requestedConversation !== conversationId.value) return
    try { focus.value = await workbenchApi.focus(requestedConversation) } catch { focus.value = null }

    const linkedRunId = String(route.query.runId ?? '')
    if (linkedRunId) selectedId.value = historyWorkItems.value.find(item => item.activeRunId === linkedRunId)?.workItemId ?? selectedId.value
    if (!nextItems.some(item => item.workItemId === selectedId.value)) selectedId.value = focus.value?.focusedWorkItemId || nextItems[0]?.workItemId || ''
    if (!selectedId.value) {
      closeStream(); detail.value = null; executionTree.value = null; budget.value = null; pendingApproval.value = null
      streamEvents.value = []; presentations.value = []; streamState.value = 'idle'; return
    }

    const requestedWorkItem = selectedId.value
    const previousId = detail.value?.workItem.workItemId
    const [nextDetail, nextTree, nextBudget, nextPresentations] = await Promise.all([
      workbenchApi.detail(requestedWorkItem), workbenchApi.executionTree(requestedWorkItem).catch(() => null),
      workbenchApi.budget(requestedWorkItem).catch(() => null),
      loadAllPresentations(requestedWorkItem),
    ])
    if (generation !== refreshGeneration || requestedWorkItem !== selectedId.value) return
    detail.value = nextDetail
    executionTree.value = nextTree
    budget.value = nextBudget
    presentations.value = nextPresentations
    if (nextDetail.workItem.activeRunId) {
      const approvals = await agentApi.approvals(100)
      if (generation !== refreshGeneration || requestedWorkItem !== selectedId.value) return
      pendingApproval.value = approvals.find(item => item.runId === nextDetail.workItem.activeRunId && item.status === 'REQUESTED') ?? null
    } else pendingApproval.value = null
    if (previousId !== requestedWorkItem || !eventSource) resetStream(nextDetail)
    error.value = ''
  } catch (cause) {
    if (generation === refreshGeneration) error.value = cause instanceof Error ? cause.message : '工作台加载失败'
  }
}

async function newTask() {
  const next = newConversationId()
  conversationId.value = next
  localStorage.setItem(CONVERSATION_KEY, next)
  rememberConversation(next)
  selectedId.value = ''
  detail.value = null
  inputs.value = []
  conversationMessages.value = []
  workItems.value = []
  streamEvents.value = []
  presentations.value = []
  followOutput.value = true
  leftDrawerOpen.value = false
  await refresh()
}

async function choose(item: WorkItem) {
  leftDrawerOpen.value = false
  followOutput.value = true
  if (item.conversationId !== conversationId.value) {
    conversationId.value = item.conversationId
    localStorage.setItem(CONVERSATION_KEY, item.conversationId)
    rememberConversation(item.conversationId)
  }
  selectedId.value = item.workItemId
  await refresh()
}

async function submit() {
  if (!content.value.trim() || busy.value) return
  followOutput.value = true
  busy.value = true; error.value = ''
  try {
    const result = await workbenchApi.submit(conversationId.value, content.value.trim())
    content.value = ''
    if (result.workItemId) selectedId.value = result.workItemId
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提交失败' }
  finally { busy.value = false }
}

async function makeFocus() {
  if (!selected.value || !focus.value) return
  await workbenchApi.switchFocus(conversationId.value, selected.value.workItemId, focus.value.version)
  await refresh()
}

async function decidePreview(approved: boolean) {
  if (!detail.value?.preview || !selected.value || busy.value) return
  busy.value = true
  try {
    approved
      ? await workbenchApi.confirm(selected.value.workItemId, detail.value.preview)
      : await workbenchApi.reject(selected.value.workItemId, detail.value.preview.previewId)
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '确认失败' }
  finally { busy.value = false }
}

async function decideApproval(approved: boolean) {
  if (!pendingApproval.value || !selected.value || controlBusy.value) return
  controlBusy.value = true
  try {
    await agentApi.decideApproval(pendingApproval.value.approvalId, approved,
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

onMounted(() => { void refresh(); timer = window.setInterval(refresh, 5000) })
onBeforeUnmount(() => { window.clearInterval(timer); closeStream() })
watch(() => [timeline.value.length, liveAnswer.value.length], () => {
  if (followOutput.value) void scrollToBottom()
})
</script>

<template>
  <div class="task-workbench-shell" :class="{ 'left-open': leftDrawerOpen, 'right-open': rightDrawerOpen }">
    <div v-if="leftDrawerOpen || rightDrawerOpen" class="workbench-scrim" @click="leftDrawerOpen = false; rightDrawerOpen = false" />

    <aside class="task-sidebar">
      <header class="task-sidebar-brand"><span>A</span><div><strong>Agent Workbench</strong><small>Enterprise Agent</small></div><button type="button" title="关闭任务栏" @click="leftDrawerOpen = false">×</button></header>
      <button class="task-new-button" type="button" @click="newTask"><span>＋</span>新建任务</button>
      <label class="task-search"><span>⌕</span><input v-model="search" placeholder="搜索任务" /></label>
      <section class="task-history">
        <h2>最近任务</h2>
        <button v-for="item in filteredHistory" :key="item.workItemId" type="button" :class="{ selected: item.workItemId === selectedId }" @click="choose(item)">
          <i :data-tone="stateTone(item)" /><div><strong>{{ item.originalGoal }}</strong><span><em>{{ targetLabel(item.activeExecutionTarget) }}</em><time>{{ relativeTime(item.updatedAt) }}</time></span></div>
        </button>
        <p v-if="!filteredHistory.length">还没有任务</p>
      </section>
      <nav class="task-product-nav" aria-label="产品导航">
        <RouterLink to="/approvals"><span>✓</span>审批中心</RouterLink>
        <RouterLink to="/incident-command"><span>△</span>事故调查</RouterLink>
        <RouterLink to="/capabilities"><span>⌘</span>能力地图</RouterLink>
        <RouterLink to="/knowledge"><span>◇</span>知识与记忆</RouterLink>
        <RouterLink to="/observability"><span>⌁</span>可观测性</RouterLink>
      </nav>
    </aside>

    <main class="task-conversation-column">
      <header class="task-header">
        <button class="task-mobile-toggle" type="button" title="打开任务列表" @click="leftDrawerOpen = true">☰</button>
        <div><h1>{{ selected?.originalGoal || '新任务' }}</h1><span v-if="selected">{{ targetLabel(selected.activeExecutionTarget) }} · {{ selected.controlState }}</span><span v-else>描述目标，系统会选择合适的执行方式</span></div>
        <div class="task-header-actions"><span v-if="copied" class="copy-confirmation">已复制</span><StatusBadge v-if="selected" :value="selected.executionState" compact /><button v-if="focus && selected && focus.focusedWorkItemId !== selected.workItemId" type="button" title="设为当前任务" @click="makeFocus">聚焦</button><button class="inspector-mobile-toggle" type="button" title="打开执行检查器" @click="rightDrawerOpen = true">◎</button></div>
      </header>

      <section ref="conversationFeed" class="task-conversation-feed" @scroll.passive="handleConversationScroll">
        <div v-if="!detail" class="task-welcome"><span>A</span><h2>今天要完成什么？</h2><p>直接描述目标。普通问答、OrderCare、事故调查和恢复规划都从这里开始。</p></div>
        <div v-else class="conversation-stream">
          <ConversationItemRenderer v-for="item in timeline" :key="item.id" :item="item" :busy="busy || controlBusy" :reviewer="reviewer" :decision-reason="decisionReason" @update:reviewer="reviewer = $event" @update:decision-reason="decisionReason = $event" @confirm-preview="decidePreview" @decide-approval="decideApproval" @copy="copyAnswer" />
          <article v-if="busy && !liveAnswer" class="conversation-loading"><span>A</span><div><i /><i /><i /><small>正在理解目标并选择执行方式</small></div></article>
        </div>
      </section>

      <button v-if="!followOutput" class="follow-output-button" type="button" @click="resumeFollowingOutput">回到底部 ↓</button>

      <footer class="task-composer">
        <div><textarea v-model="content" rows="2" placeholder="描述目标，或为当前任务补充要求…" @keydown.ctrl.enter.prevent="submit" /><button type="button" title="发送" :disabled="busy || !content.trim()" @click="submit">↑</button></div>
        <p><span v-if="error" class="composer-error">{{ error }}</span><span v-else>Ctrl + Enter 发送 · 运行中的任务可接收补充指令</span></p>
      </footer>
    </main>

    <ExecutionInspector v-if="detail" :detail="detail" :tree="executionTree" :events="streamEvents" :budget="budget" :approval="pendingApproval" :stream-state="streamState" :final-answer-at="finalAnswerAt" :busy="controlBusy" @command="executeCommand" />
    <aside v-else class="execution-inspector inspector-placeholder"><span>◎</span><strong>执行检查器</strong><p>任务开始后，这里会显示状态、活动、Agents 与证据。</p></aside>
  </div>
</template>
