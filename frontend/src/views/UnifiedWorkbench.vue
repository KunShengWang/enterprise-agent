<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { workbenchApi } from '../api/workbench'
import { renderMarkdown } from '../utils/markdown'
import type { ExecutionAgentNode, WorkEvent, WorkExecutionTree, WorkFocus, WorkInput, WorkItem, WorkItemDetail, WorkLink, WorkStreamItem } from '../types/workbench'

const conversationId = ref(localStorage.getItem('unified-workbench-conversation') || `workbench-${new Date().toISOString().slice(0, 10)}`)
const content = ref('')
const inputs = ref<WorkInput[]>([])
const workItems = ref<WorkItem[]>([])
const focus = ref<WorkFocus | null>(null)
const selectedId = ref('')
const detail = ref<WorkItemDetail | null>(null)
const busy = ref(false)
const error = ref('')
const streamEvents = ref<WorkEvent[]>([])
const executionTree = ref<WorkExecutionTree | null>(null)
const expandedNodeId = ref('')
const answer = ref('')
const streamState = ref<'idle' | 'connecting' | 'live' | 'recovering' | 'error'>('idle')
const workCursor = ref(-1)
const runCursor = ref(-1)
let timer = 0
let reconnectTimer = 0
let eventSource: EventSource | null = null
let streamGeneration = 0
let treeRefreshBusy = false
const seenWorkEvents = new Set<string>()
const seenRunEvents = new Set<string>()

const selected = computed(() => workItems.value.find(item => item.workItemId === selectedId.value) ?? null)
const routeReason = computed(() => String(detail.value?.routingDecision?.decision?.reason ?? '等待 Router 形成可审计决策'))
const renderedAnswer = computed(() => renderMarkdown(answer.value))
const assessmentPresent = computed(() => Object.keys(executionTree.value?.assessment ?? {}).length > 0)

function compactId(value: string) {
  return value.length > 22 ? `${value.slice(0, 10)}…${value.slice(-8)}` : value
}

function roleLabel(role: string) {
  if (role === 'COMMANDER') return 'Commander · 调查指挥'
  if (role === 'REVIEWER') return 'Reviewer · 证据审查'
  if (role === 'RECOVERY_PLANNER') return 'Recovery Planner · 恢复规划'
  if (role === 'GENERAL_AGENT') return 'General Agent'
  if (role === 'ORDERCARE_CASE') return 'OrderCare Agent'
  const specialist = role.match(/^SPECIALIST:([^:]+)/)?.[1]
  const labels: Record<string, string> = {
    ORDER_ANALYST: 'Order Specialist', INVENTORY_ANALYST: 'Inventory Specialist',
    MQ_ANALYST: 'MQ Specialist', SOP_ANALYST: 'SOP Specialist',
  }
  return specialist ? (labels[specialist] ?? specialist) : role
}

function nodeState(node: ExecutionAgentNode) {
  const status = node.status.toUpperCase()
  if (['FAILED', 'CANCELLED', 'TIMED_OUT', 'REJECTED', 'MANUAL_REVIEW'].some(value => status.includes(value))) return 'failed'
  if (['COMPLETED', 'SUCCEEDED', 'ASSESSED', 'CLOSED'].some(value => status.includes(value))) return 'completed'
  if (['PENDING', 'CREATED', 'TRACE_UNAVAILABLE'].some(value => status.includes(value))) return 'pending'
  return 'active'
}

function toolSpans(node: ExecutionAgentNode) {
  return node.trace?.spans.filter(span => ['TOOL', 'RAG'].includes(span.kind)) ?? []
}

function lastStage(node: ExecutionAgentNode) {
  return node.trace?.replayEvents.at(-1)?.eventType || node.status
}

async function refreshExecutionTree(workItemId: string) {
  if (treeRefreshBusy) return
  treeRefreshBusy = true
  try {
    const next = await workbenchApi.executionTree(workItemId)
    if (selectedId.value === workItemId) executionTree.value = next
  } catch (cause) {
    if (selectedId.value === workItemId) error.value = cause instanceof Error ? cause.message : '执行树加载失败'
  } finally { treeRefreshBusy = false }
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
  workCursor.value = Math.max(workCursor.value, Number(match[1]))
  runCursor.value = Math.max(runCursor.value, Number(match[2]))
}

function parseStreamEvent(event: MessageEvent<string>): WorkStreamItem | null {
  try { return JSON.parse(event.data) as WorkStreamItem }
  catch { error.value = '事件流返回了无法解析的数据'; return null }
}

async function loadAllEvents(workItemId: string) {
  const events: WorkEvent[] = []
  let cursor = -1
  for (;;) {
    const page = await workbenchApi.events(workItemId, cursor, 500)
    if (!page.length) break
    events.push(...page)
    cursor = page[page.length - 1].sequence
    if (page.length < 500) break
  }
  return events
}

function historyIsContinuous(events: WorkEvent[]) {
  return events.every((event, index) => index === 0 || event.sequence === events[index - 1].sequence + 1)
}

async function recoverGap(workItemId: string) {
  closeStream()
  streamState.value = 'recovering'
  try {
    const historical = await loadAllEvents(workItemId)
    if (!historyIsContinuous(historical)) {
      streamState.value = 'error'
      error.value = '执行时间线存在持久化序号缺口，已停止增量追加'
      return
    }
    streamEvents.value = historical
    seenWorkEvents.clear()
    historical.forEach(event => seenWorkEvents.add(event.eventId))
    workCursor.value = historical.at(-1)?.sequence ?? -1
    runCursor.value = -1
    answer.value = ''
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
  const source = new EventSource(workbenchApi.streamUrl(workItemId, workCursor.value, runCursor.value))
  eventSource = source
  source.onopen = () => { if (generation === streamGeneration) streamState.value = 'live' }
  source.addEventListener('work-event', raw => {
    if (generation !== streamGeneration) return
    const event = parseStreamEvent(raw as MessageEvent<string>)
    if (!event) return
    if (event.workSequence > workCursor.value + 1) { void recoverGap(workItemId); return }
    applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
    if (!event.eventId || seenWorkEvents.has(event.eventId)) return
    seenWorkEvents.add(event.eventId)
    streamEvents.value.push({
      eventId: event.eventId,
      sequence: event.workSequence,
      eventType: event.eventType,
      phase: String(event.payload.phase ?? ''),
      summary: event.content,
      projectedAt: event.createdAt,
    })
    if (['AGENT_RUN', 'INCIDENT', 'RECOVERY_PLAN'].includes(event.sourceType)) {
      void refreshExecutionTree(workItemId)
    }
  })
  source.addEventListener('model-delta', raw => {
    if (generation !== streamGeneration) return
    const event = parseStreamEvent(raw as MessageEvent<string>)
    if (!event) return
    applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
    if (!event.eventId || seenRunEvents.has(event.eventId)) return
    seenRunEvents.add(event.eventId)
    answer.value += event.content
  })
  source.addEventListener('heartbeat', raw => {
    if (generation !== streamGeneration) return
    const event = parseStreamEvent(raw as MessageEvent<string>)
    if (event) applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
  })
  source.addEventListener('gap', () => { if (generation === streamGeneration) void recoverGap(workItemId) })
  source.addEventListener('sync-error', () => {
    if (generation !== streamGeneration) return
    streamState.value = 'error'
    source.close()
  })
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
  workCursor.value = streamEvents.value.at(-1)?.sequence ?? -1
  runCursor.value = -1
  answer.value = ''
  connectStream(next.workItem.workItemId)
}

async function refresh() {
  try {
    const [nextInputs, nextItems] = await Promise.all([
      workbenchApi.inputs(conversationId.value), workbenchApi.workItems(conversationId.value),
    ])
    inputs.value = nextInputs
    workItems.value = nextItems
    try { focus.value = await workbenchApi.focus(conversationId.value) } catch { focus.value = null }
    if (!selectedId.value) selectedId.value = focus.value?.focusedWorkItemId || nextItems[0]?.workItemId || ''
    if (selectedId.value) {
      const previousId = detail.value?.workItem.workItemId
      const [nextDetail, nextTree] = await Promise.all([
        workbenchApi.detail(selectedId.value), workbenchApi.executionTree(selectedId.value),
      ])
      detail.value = nextDetail
      executionTree.value = nextTree
      if (previousId !== nextDetail.workItem.workItemId || !eventSource) resetStream(nextDetail)
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '统一工作台加载失败'
  }
}

async function submit() {
  if (!content.value.trim() || busy.value) return
  busy.value = true; error.value = ''
  localStorage.setItem('unified-workbench-conversation', conversationId.value)
  try {
    const result = await workbenchApi.submit(conversationId.value, content.value.trim())
    content.value = ''
    if (result.workItemId) selectedId.value = result.workItemId
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提交失败' }
  finally { busy.value = false }
}

async function choose(item: WorkItem) {
  if (item.workItemId === selectedId.value && detail.value) return
  selectedId.value = item.workItemId
  const [nextDetail, nextTree] = await Promise.all([
    workbenchApi.detail(item.workItemId), workbenchApi.executionTree(item.workItemId),
  ])
  detail.value = nextDetail
  executionTree.value = nextTree
  expandedNodeId.value = ''
  resetStream(nextDetail)
}
async function makeFocus(item: WorkItem) { if (!focus.value) return; await workbenchApi.switchFocus(conversationId.value, item.workItemId, focus.value.version); await refresh() }
async function decide(approved: boolean) {
  const preview = detail.value?.preview
  if (!preview || !selected.value) return
  busy.value = true; error.value = ''
  try { approved ? await workbenchApi.confirm(selected.value.workItemId, preview) : await workbenchApi.reject(selected.value.workItemId, preview.previewId); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '确认失败' }
  finally { busy.value = false }
}
function targetRoute(link: WorkLink) {
  if (link.linkType === 'INCIDENT') return { path: '/incident-command', query: { incidentId: link.linkedId } }
  if (link.linkType === 'RECOVERY_PLAN') {
    const typedPayload = detail.value?.routingDecision?.validation?.typedPayload as Record<string, unknown> | undefined
    const incidentId = detail.value?.workItem.activeIncidentId || String(typedPayload?.incidentId ?? '')
    return { path: '/incident-command', query: { incidentId, planId: link.linkedId } }
  }
  return { path: '/', query: { runId: link.linkedId } }
}
function stateClass(state: string) {
  if (['DISPATCHED', 'CLOSED'].includes(state)) return 'work-ok'
  if (['MANUAL_REVIEW', 'ABANDONED'].includes(state)) return 'work-bad'
  return 'work-active'
}

onMounted(() => { refresh(); timer = window.setInterval(refresh, 5000) })
onBeforeUnmount(() => { window.clearInterval(timer); closeStream() })
</script>

<template>
  <div class="unified-workbench">
    <aside class="panel unified-history">
      <p class="eyebrow">UNIFIED AGENT WORKBENCH · M2</p>
      <h2>一个入口，四种执行目标</h2>
      <label class="field-label">Conversation ID</label>
      <input v-model="conversationId" @change="refresh" />
      <div class="unified-task-list">
        <button v-for="item in workItems" :key="item.workItemId" :class="{ selected: item.workItemId === selectedId }" @click="choose(item)">
          <span :class="stateClass(item.controlState)" />
          <strong>{{ item.originalGoal }}</strong>
          <small>{{ item.activeExecutionTarget || 'ROUTING' }} · {{ item.controlState }}</small>
          <code>{{ item.workItemId }}</code>
        </button>
        <p v-if="!workItems.length" class="compact-empty">还没有任务。直接描述目标，系统会选择执行方式。</p>
      </div>
    </aside>

    <main class="panel unified-main">
      <header class="unified-header">
        <div><p class="eyebrow">GOAL ROUTING</p><h2>{{ selected?.originalGoal || '描述你想完成的目标' }}</h2></div>
        <div v-if="selected" class="action-row">
          <button v-if="focus && focus.focusedWorkItemId !== selected.workItemId" class="secondary-button" @click="makeFocus(selected)">设为当前任务</button>
          <span class="status-badge"><i />{{ selected.controlState }}</span>
        </div>
      </header>

      <section v-if="detail" class="unified-detail">
        <article class="route-card">
          <div><span>系统选择</span><strong>{{ detail.workItem.activeExecutionTarget || '正在路由' }}</strong></div>
          <p>{{ routeReason }}</p>
          <div class="route-meta"><code>{{ detail.routingDecision?.decisionId || detail.workItem.routingFailureCode || 'routing pending' }}</code><span>{{ detail.workItem.executionState }}</span></div>
        </article>

        <article v-if="detail.preview" class="preview-card">
          <div><p class="eyebrow">EXPLICIT CONFIRMATION</p><h3>启动事故调查前确认范围</h3></div>
          <p>Preview {{ detail.preview.previewId }} · v{{ detail.preview.previewVersion }} · {{ detail.preview.status }}</p>
          <pre>{{ JSON.stringify(detail.preview.payload, null, 2) }}</pre>
          <div v-if="detail.preview.status === 'ACTIVE'" class="action-row">
            <button class="primary-button" :disabled="busy" @click="decide(true)">确认并启动</button>
            <button class="danger-button" :disabled="busy" @click="decide(false)">拒绝</button>
          </div>
        </article>

        <article v-if="detail.links.length" class="target-links">
          <p class="eyebrow">EXECUTION TARGET</p>
          <RouterLink v-for="link in detail.links" :key="link.linkedId" :to="targetRoute(link)">
            <strong>{{ link.linkType }}</strong><code>{{ link.linkedId }}</code><span>打开专项视图 →</span>
          </RouterLink>
        </article>

        <section v-if="executionTree && (executionTree.coordinator || executionTree.agents.length)" class="execution-tree">
          <header class="execution-tree-head">
            <div><p class="eyebrow">EXECUTION TREE · {{ executionTree.treeType }}</p><h3>Agent 执行过程</h3></div>
            <div class="tree-metrics">
              <span><strong>{{ executionTree.metrics.agentNodes }}</strong>Agent</span>
              <span><strong>{{ executionTree.metrics.modelCalls }}</strong>模型轮次</span>
              <span><strong>{{ executionTree.metrics.toolCalls }}</strong>工具</span>
              <span><strong>{{ executionTree.metrics.promptTokens + executionTree.metrics.completionTokens }}</strong>Token</span>
            </div>
          </header>

          <article v-if="executionTree.coordinator" class="coordinator-row">
            <span class="node-icon">C</span>
            <div><strong>{{ executionTree.coordinator.label }}</strong><p>{{ executionTree.coordinator.span.summary }}</p></div>
            <code>synthetic · model calls {{ executionTree.coordinator.modelCalls }}</code>
          </article>

          <div class="agent-node-grid">
            <article v-for="node in executionTree.agents" :key="node.nodeId" class="agent-node" :data-state="nodeState(node)">
              <header>
                <span class="node-icon">{{ roleLabel(node.role).slice(0, 1) }}</span>
                <div><strong>{{ roleLabel(node.role) }}</strong><small>Attempt {{ node.attempt }}/{{ node.maxAttempts }} · {{ node.status }}</small></div>
                <i />
              </header>
              <p>{{ node.objective }}</p>
              <div class="node-stage"><span>当前阶段</span><strong>{{ lastStage(node) }}</strong></div>
              <div class="node-metrics">
                <span>模型 {{ node.metrics.modelCalls }}</span><span>工具 {{ node.metrics.toolCalls }}</span>
                <span>Token {{ node.metrics.promptTokens + node.metrics.completionTokens }}</span><span>{{ Math.round(node.metrics.durationMs) }}ms</span>
              </div>
              <code v-if="node.runId" class="node-run-id">{{ compactId(node.runId) }}</code>
              <p v-if="node.error" class="node-error">{{ node.error }}</p>
              <button class="node-detail-button" type="button" @click="expandedNodeId = expandedNodeId === node.nodeId ? '' : node.nodeId">
                {{ expandedNodeId === node.nodeId ? '收起详情' : '查看阶段、工具与证据' }}
              </button>
              <div v-if="expandedNodeId === node.nodeId" class="node-details">
                <div><strong>阶段</strong><ol><li v-for="stage in node.trace?.replayEvents.slice(-8)" :key="stage.sequence"><span>{{ stage.eventType }}</span><small>{{ stage.summary }}</small></li></ol><p v-if="!node.trace?.replayEvents.length">尚无 Runtime 阶段事件</p></div>
                <div><strong>工具</strong><ol><li v-for="tool in toolSpans(node)" :key="tool.spanId"><span>{{ tool.name }}</span><small>{{ tool.status }} · {{ tool.durationMs }}ms</small></li></ol><p v-if="!toolSpans(node).length">该 Agent 尚无工具调用</p></div>
                <div><strong>Evidence</strong><ol><li v-for="item in node.evidence" :key="item.evidenceId"><span>{{ item.evidenceSubtype }}</span><small>{{ compactId(item.evidenceId) }}</small></li></ol><p v-if="!node.evidence.length">该节点未产生业务 Evidence</p></div>
              </div>
            </article>
          </div>

          <div v-if="executionTree.conflicts.length || assessmentPresent || executionTree.recoveryPlans.length" class="execution-artifacts">
            <section v-if="executionTree.conflicts.length">
              <p class="eyebrow">JAVA CONFLICTS</p>
              <article v-for="conflict in executionTree.conflicts" :key="conflict.conflictId"><strong>{{ conflict.severity }} · {{ conflict.conflictType }}</strong><span>{{ conflict.metricKey }}</span><code>{{ compactId(conflict.conflictId) }}</code></article>
            </section>
            <section v-if="assessmentPresent">
              <p class="eyebrow">REVIEWER ASSESSMENT</p>
              <strong>{{ executionTree.assessment.outcome || 'ASSESSMENT' }} · {{ executionTree.assessment.riskLevel || '' }}</strong>
              <details><summary>查看结构化结论</summary><pre>{{ JSON.stringify(executionTree.assessment, null, 2) }}</pre></details>
            </section>
            <section v-if="executionTree.recoveryPlans.length">
              <p class="eyebrow">RECOVERY PROPOSALS</p>
              <article v-for="plan in executionTree.recoveryPlans" :key="plan.planId"><strong>{{ plan.status }} · {{ plan.outcome }}</strong><span>{{ plan.items.length }} 个处置项</span><code>{{ compactId(plan.planId) }}</code></article>
            </section>
          </div>
        </section>

        <article v-if="detail.workItem.activeExecutionTarget === 'GENERAL_AGENT' || answer" class="stream-answer">
          <div>
            <div><p class="eyebrow">PRIMARY RUN</p><h3>Agent 回答</h3></div>
            <span class="stream-indicator" :data-state="streamState">{{ streamState }}</span>
          </div>
          <div v-if="answer" class="answer-content" v-html="renderedAnswer" />
          <p v-else class="compact-empty">等待主 Run 输出。子 Agent 的模型增量不会混入这里。</p>
        </article>

        <article class="local-events">
          <div><h3>统一执行时间线</h3><small>SSE · cursor w:{{ workCursor }} / r:{{ runCursor }}</small></div>
          <ol>
            <li v-for="event in streamEvents" :key="event.eventId">
              <span :class="stateClass(event.phase || event.eventType)" /><div><strong>{{ event.summary }}</strong><code>#{{ event.sequence }} · {{ event.eventType }}<template v-if="event.phase"> · {{ event.phase }}</template></code></div>
            </li>
          </ol>
        </article>
      </section>

      <section v-else class="unified-empty"><strong>无需先选择页面</strong><p>普通问答、OrderCare 单案例、事故调查和恢复计划都从同一个输入框开始。</p></section>

      <footer class="unified-composer">
        <textarea v-model="content" rows="3" placeholder="例如：调查批次 BATCH-20260720-01 的异常订单与死信，并说明是否需要人工确认" @keydown.ctrl.enter.prevent="submit" />
        <div><span v-if="error" class="inline-error">{{ error }}</span><small>Ctrl + Enter 发送 · 身份由服务端注入</small><button class="primary-button" :disabled="busy || !content.trim()" @click="submit">{{ busy ? '处理中…' : '发送目标' }}</button></div>
      </footer>
    </main>
  </div>
</template>
