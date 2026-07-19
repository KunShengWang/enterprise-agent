<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { incidentApi } from '../api/incident'
import StatusBadge from '../components/StatusBadge.vue'
import type {
  IncidentAggregate,
  IncidentEvidence,
  IncidentRecoveryPlan,
  IncidentStreamItem,
  IncidentTask,
  IncidentTrace,
  RuntimeReplayEvent,
  RuntimeRunTrace,
} from '../types/incident'

interface AgentRunNode {
  key: string
  runRole: string
  taskId: string
  trace?: RuntimeRunTrace
  task?: IncidentTask
  evidence: IncidentEvidence[]
}

const alertBatchId = ref(`BATCH-${new Date().toISOString().slice(0, 10).replaceAll('-', '')}-01`)
const alertType = ref('ORDER_STATE_INCONSISTENCY')
const symptom = ref('订单、库存扣减与库存释放死信的统计口径出现异常，请完成只读调查并输出证据化结论。')
const requestIdsText = ref('ORDERCARE-M05-REQUEST')
const queuesText = ref('floworder.order.state.dlq')
const aggregate = ref<IncidentAggregate | null>(null)
const trace = ref<IncidentTrace | null>(null)
const incidentId = ref('')
const busy = ref(false)
const error = ref('')
const recoveryPlans = ref<IncidentRecoveryPlan[]>([])
const recoveryObjective = ref('根据已确认的库存释放死信证据，生成受控恢复 Proposal；不得扩展调查范围或直接执行。')
const reviewer = ref('incident-operator')
const approvalComment = ref('已核对不可变预演、影响范围、证据引用和过期时间')
const recoveryBusy = ref(false)
const decidingItemId = ref('')
const expandedRuns = ref<string[]>([])
let stream: EventSource | null = null
let refreshTimer: number | null = null
let recoveryPollTimer: number | null = null
let livePollTimer: number | null = null
let liveRefreshBusy = false

const terminal = computed(() => ['ASSESSED', 'PARTIAL', 'MANUAL_REVIEW', 'FAILED', 'CANCELLED']
  .includes(aggregate.value?.incident.status ?? ''))
const conflicts = computed(() => aggregate.value?.events.filter(event => event.eventType === 'EVIDENCE_CONFLICT_DETECTED') ?? [])
const progress = computed(() => {
  const status = aggregate.value?.incident.status ?? 'CREATED'
  const steps = ['CREATED', 'PLANNING', 'INVESTIGATING', 'CHECKING_CONSISTENCY', 'REVIEWING']
  const terminalIndex = terminal.value ? steps.length : Math.max(0, steps.indexOf(status))
  return Math.round((terminalIndex / steps.length) * 100)
})
const activeRecoveryPlan = computed(() => recoveryPlans.value[0] ?? null)
const canPlanRecovery = computed(() => aggregate.value?.incident.status === 'ASSESSED')
const recoveryPlanActive = computed(() => activeRecoveryPlan.value
  && !['COMPLETED', 'FAILED', 'CANCELLED'].includes(activeRecoveryPlan.value.status))
const agentRunNodes = computed<AgentRunNode[]>(() => {
  if (!aggregate.value) return []
  const nodes: AgentRunNode[] = []
  const tracedTaskIds = new Set<string>()
  const childRuns = trace.value?.childRuns ?? []
  childRuns.forEach(child => {
    if (child.taskId) tracedTaskIds.add(child.taskId)
    nodes.push({
      key: `${child.runRole}:${child.trace.traceId}`,
      runRole: child.runRole,
      taskId: child.taskId,
      trace: child.trace,
      task: aggregate.value?.tasks.find(task => task.taskId === child.taskId),
      evidence: aggregate.value?.evidence.filter(item => item.childRunId === child.trace.traceId) ?? [],
    })
  })
  if (!childRuns.some(child => child.runRole === 'COMMANDER')
      && ['CREATED', 'PLANNING'].includes(aggregate.value.incident.status)) {
    nodes.push({ key: 'commander-pending', runRole: 'COMMANDER', taskId: '', evidence: [] })
  }
  aggregate.value.tasks.filter(task => !tracedTaskIds.has(task.taskId)).forEach(task => {
    nodes.push({
      key: `task:${task.taskId}`,
      runRole: `SPECIALIST:${task.role}:ATTEMPT_${task.attempt + 1}`,
      taskId: task.taskId,
      task,
      evidence: aggregate.value?.evidence.filter(item => item.taskId === task.taskId) ?? [],
    })
  })
  if (!childRuns.some(child => child.runRole === 'REVIEWER')
      && ['CHECKING_CONSISTENCY', 'REVIEWING'].includes(aggregate.value.incident.status)) {
    nodes.push({ key: 'reviewer-pending', runRole: 'REVIEWER', taskId: '', evidence: [] })
  }
  const rank = (role: string) => role === 'COMMANDER' ? 0
    : role.startsWith('SPECIALIST:') ? 1
      : role === 'REVIEWER' ? 2
        : role === 'RECOVERY_PLANNER' ? 3 : 4
  return nodes.sort((left, right) => rank(left.runRole) - rank(right.runRole))
})

function lines(value: string) {
  return [...new Set(value.split(/[\s,，;；]+/).map(item => item.trim()).filter(Boolean))]
}

function dateTime(value?: string) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value)) : '—'
}

function duration(value?: number) {
  if (!value) return '0ms'
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`
}

function roleLabel(role: string) {
  if (role === 'COMMANDER') return 'Commander · 调查指挥官'
  if (role === 'REVIEWER') return 'Reviewer · 证据审查员'
  if (role === 'RECOVERY_PLANNER') return 'Recovery Planner · 恢复规划员'
  const specialist = role.match(/^SPECIALIST:([^:]+)/)?.[1]
  const labels: Record<string, string> = {
    ORDER_ANALYST: 'Order Specialist · 订单分析',
    INVENTORY_ANALYST: 'Inventory Specialist · 库存分析',
    MQ_ANALYST: 'MQ Specialist · 消息链路分析',
    SOP_ANALYST: 'SOP Specialist · 处置规范检索',
  }
  return specialist ? (labels[specialist] ?? specialist) : role
}

function roleDescription(node: AgentRunNode) {
  if (node.task?.objective) return node.task.objective
  if (node.runRole === 'COMMANDER') return '理解事故范围，输出受限 DelegationPlan，不直接调用业务工具'
  if (node.runRole === 'REVIEWER') return '审查结构化 Evidence 与 Java Conflict，只能引用有效证据形成结论'
  if (node.runRole === 'RECOVERY_PLANNER') return '基于已确认结论提出 ProposalRequest，不拥有恢复执行权限'
  return '等待 Orchestrator 分配任务'
}

const eventLabels: Record<string, string> = {
  RUN_STARTED: '接收任务并启动 Run',
  CONTEXT_PREPARED: '读取并组装上下文',
  MODEL_STARTED: '模型开始推理与决策',
  MODEL_COMPLETED: '模型本轮决策完成',
  TOOL_REQUESTED: '模型请求只读能力',
  TOOL_STARTED: '工具开始执行',
  TOOL_COMPLETED: '工具返回结构化结果',
  APPROVAL_REQUIRED: '等待人工审批',
  RUN_WAITING_INPUT: '保存检查点并等待定向补证',
  RUN_RESUMED: '从持久化检查点继续',
  RUN_COMPLETED: 'Run 执行完成',
  RUN_FAILED: 'Run 执行失败',
  RUN_STOPPED: 'Run 已停止',
  CONTEXT_COMPACTED: '上下文压缩完成',
}

function significantEvents(run?: RuntimeRunTrace) {
  return (run?.replayEvents ?? []).filter(event => event.eventType !== 'MODEL_DELTA')
}

function eventLabel(event: RuntimeReplayEvent) {
  return eventLabels[event.eventType] ?? event.eventType
}

function currentPhase(node: AgentRunNode) {
  if (!node.trace) {
    const status = node.task?.status
    if (status === 'PENDING') return '等待调度'
    if (status === 'CLAIMED') return '已领取任务'
    if (status === 'RUNNING') return '正在执行'
    return node.runRole === 'COMMANDER' ? '正在生成调查分工' : '等待 Run Trace'
  }
  const events = significantEvents(node.trace)
  const latest = events.at(-1)
  if (latest) return eventLabel(latest)
  return node.trace.status === 'COMPLETED' ? 'Run 执行完成' : '正在初始化'
}

function nodeStatus(node: AgentRunNode) {
  return node.trace?.status ?? node.task?.status ?? 'RUNNING'
}

function toolSpans(run?: RuntimeRunTrace) {
  return (run?.spans ?? []).filter(span => span.kind === 'TOOL')
}

function compact(value: unknown, limit = 180) {
  const text = typeof value === 'string' ? value : JSON.stringify(value)
  if (!text) return '—'
  return text.length > limit ? `${text.slice(0, limit)}…` : text
}

function toggleRun(key: string) {
  expandedRuns.value = expandedRuns.value.includes(key)
    ? expandedRuns.value.filter(item => item !== key)
    : [...expandedRuns.value, key]
}

function runExpanded(key: string) {
  return expandedRuns.value.includes(key)
}

function runArtifact(node: AgentRunNode) {
  if (node.runRole === 'COMMANDER') return aggregate.value?.incident.delegationPlan
  if (node.runRole === 'REVIEWER') return aggregate.value?.incident.assessment
  return node.task?.outputSummary
}

function hasArtifact(node: AgentRunNode) {
  const value = runArtifact(node)
  return Boolean(value && Object.keys(value).length)
}

async function start() {
  closeStream()
  busy.value = true
  error.value = ''
    aggregate.value = null
    trace.value = null
    recoveryPlans.value = []
  try {
    const started = await incidentApi.start({
      alertBatchId: alertBatchId.value,
      alertType: alertType.value,
      detectedAt: new Date().toISOString(),
      symptom: symptom.value,
      candidateRequestIds: lines(requestIdsText.value),
      queueNames: lines(queuesText.value),
    })
    incidentId.value = started.incidentId
    await refresh()
    openStream()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '事故调查启动失败'
  } finally {
    busy.value = false
  }
}

async function refresh() {
  if (!incidentId.value) return
  try {
    aggregate.value = await incidentApi.find(incidentId.value)
    await refreshTrace()
    if (terminal.value) {
      recoveryPlans.value = await incidentApi.recoveryPlans(incidentId.value)
      if (recoveryPlanActive.value && recoveryPollTimer === null) startRecoveryPolling()
      closeStream()
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '调查状态刷新失败'
  }
}

async function refreshTrace() {
  if (!incidentId.value) return
  try {
    trace.value = await incidentApi.trace(incidentId.value)
  } catch {
    // Run 关联尚未落库时 Trace 可以短暂为空，Aggregate 主流程继续刷新。
  }
}

async function startRecoveryPlan() {
  if (!incidentId.value || !canPlanRecovery.value) return
  recoveryBusy.value = true
  error.value = ''
  try {
    await incidentApi.startRecoveryPlan(incidentId.value, {
      requestKey: `recovery-${incidentId.value}-${crypto.randomUUID()}`,
      objective: recoveryObjective.value,
    })
    await refreshRecoveryPlans()
    startRecoveryPolling()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '恢复计划创建失败'
  } finally {
    recoveryBusy.value = false
  }
}

async function refreshRecoveryPlans() {
  if (!incidentId.value) return
  recoveryPlans.value = await incidentApi.recoveryPlans(incidentId.value)
  await refreshTrace()
  if (!recoveryPlanActive.value) stopRecoveryPolling()
}

function startRecoveryPolling() {
  stopRecoveryPolling()
  recoveryPollTimer = window.setInterval(() => {
    refreshRecoveryPlans().catch(reason => {
      error.value = reason instanceof Error ? reason.message : '恢复计划刷新失败'
    })
  }, 1200)
}

function stopRecoveryPolling() {
  if (recoveryPollTimer !== null) window.clearInterval(recoveryPollTimer)
  recoveryPollTimer = null
}

async function decideRecoveryItem(plan: IncidentRecoveryPlan, itemId: string, approved: boolean) {
  decidingItemId.value = itemId
  error.value = ''
  try {
    await incidentApi.decideRecoveryItem(
      incidentId.value, plan.planId, itemId, approved, reviewer.value, approvalComment.value,
    )
    await refreshRecoveryPlans()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '审批执行失败'
  } finally {
    decidingItemId.value = ''
  }
}

function openStream() {
  if (!incidentId.value) return
  startLivePolling()
  stream = new EventSource(incidentApi.streamUrl(incidentId.value))
  stream.onmessage = event => {
    const item = JSON.parse(event.data) as IncidentStreamItem
    if (item.type === 'EVENT') scheduleRefresh()
  }
  stream.onerror = () => scheduleRefresh()
}

function startLivePolling() {
  stopLivePolling()
  livePollTimer = window.setInterval(async () => {
    if (liveRefreshBusy || terminal.value) return
    liveRefreshBusy = true
    try {
      await refresh()
    } finally {
      liveRefreshBusy = false
    }
  }, 1000)
}

function stopLivePolling() {
  if (livePollTimer !== null) window.clearInterval(livePollTimer)
  livePollTimer = null
}

function scheduleRefresh() {
  if (refreshTimer !== null) return
  refreshTimer = window.setTimeout(async () => {
    refreshTimer = null
    await refresh()
  }, 180)
}

function closeStream() {
  stream?.close()
  stream = null
  stopLivePolling()
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  refreshTimer = null
}

onBeforeUnmount(() => {
  closeStream()
  stopRecoveryPolling()
})
</script>

<template>
  <div class="incident-page">
    <header class="incident-hero">
      <div>
        <p class="eyebrow">ORDERCARE INCIDENT COMMAND · PHASE 1 + PHASE 2 + PHASE 3</p>
        <h2>异常订单事故调查与证据指挥台</h2>
        <p>Commander 只做受限分工，Specialist 只调用白名单只读能力，Java 完成事实投影与冲突检查，Reviewer 只能引用已落库证据。</p>
      </div>
      <div class="phase-chip">INVESTIGATE → CONTROLLED RECOVERY</div>
    </header>

    <section class="command-grid">
      <form class="incident-form" @submit.prevent="start">
        <div class="section-title"><span>01</span><div><strong>冻结调查范围</strong><small>请求中的 ID 和队列会被服务端生成 snapshotId 与 scopeHash</small></div></div>
        <label>告警批次<input v-model="alertBatchId" required /></label>
        <label>告警类型<input v-model="alertType" required /></label>
        <label>异常现象<textarea v-model="symptom" rows="4" required /></label>
        <label>候选 requestId<textarea v-model="requestIdsText" rows="4" placeholder="每行一个，最多 100 个" required /></label>
        <label>RabbitMQ 队列白名单<textarea v-model="queuesText" rows="2" placeholder="每行一个，可留空" /></label>
        <button class="start-button" type="submit" :disabled="busy">{{ busy ? '正在创建快照…' : '启动只读事故调查' }}</button>
        <p v-if="error" class="incident-error">{{ error }}</p>
      </form>

      <div class="incident-console">
        <div v-if="!aggregate" class="empty-console">
          <span>△</span><strong>等待调查任务</strong><p>提交后这里会在同一窗口实时展示状态、子 Run、证据、冲突和最终评估。</p>
        </div>
        <template v-else>
          <div class="console-head">
            <div><p class="eyebrow">INCIDENT ID</p><strong>{{ aggregate.incident.incidentId }}</strong></div>
            <StatusBadge :value="aggregate.incident.status" />
          </div>
          <div class="progress-track"><i :style="{ width: `${progress}%` }" /></div>
          <div class="metric-row">
            <div><span>Tasks</span><strong>{{ aggregate.tasks.length }}</strong></div>
            <div><span>Evidence</span><strong>{{ aggregate.evidence.length }}</strong></div>
            <div><span>Conflicts</span><strong>{{ conflicts.length }}</strong></div>
            <div><span>Clarify</span><strong>{{ aggregate.incident.clarificationCount }}/1</strong></div>
          </div>

          <section class="console-section">
            <div class="section-title"><span>02</span><div><strong>实时 Multi-Agent 执行树</strong><small>每个 Agent 是独立 Run；展开可查看模型轮次、工具调用、Evidence 和持久化检查点</small></div></div>
            <div class="agent-run-board">
              <article class="coordinator-node">
                <div class="run-node-icon">C</div>
                <div class="run-node-main">
                  <div class="run-node-title"><div><strong>Coordinator · 确定性编排器</strong><small>冻结范围、调度子 Agent、执行冲突检查并推进状态机；不调用模型</small></div><StatusBadge :value="aggregate.incident.status" compact /></div>
                  <div class="run-live-phase"><i :class="{ pulse: !terminal }" /><span>{{ terminal ? '事故编排已收敛' : `正在推进 ${aggregate.incident.status}` }}</span><code>synthetic span · model calls 0</code></div>
                </div>
              </article>

              <div class="agent-branch-label"><span />受控委派与并行执行<span /></div>

              <article v-for="node in agentRunNodes" :key="node.key" class="agent-run-card" :class="{ expanded: runExpanded(node.key) }">
                <header>
                  <div class="run-node-icon" :class="node.runRole.toLowerCase().replaceAll(':', '-')">{{ node.runRole === 'COMMANDER' ? 'C' : node.runRole === 'REVIEWER' ? 'R' : node.runRole === 'RECOVERY_PLANNER' ? 'P' : 'S' }}</div>
                  <div class="run-node-main">
                    <div class="run-node-title">
                      <div><strong>{{ roleLabel(node.runRole) }}</strong><small>{{ roleDescription(node) }}</small></div>
                      <StatusBadge :value="nodeStatus(node)" compact />
                    </div>
                    <div class="run-live-phase"><i :class="{ pulse: !['COMPLETED', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(nodeStatus(node)) }" /><span>{{ currentPhase(node) }}</span><code>{{ node.trace?.traceId || node.task?.childRunId || 'RUN PENDING' }}</code></div>
                    <div class="run-metrics">
                      <span>耗时 <strong>{{ duration(node.trace?.durationMs) }}</strong></span>
                      <span>模型 <strong>{{ node.trace?.metrics.modelCalls ?? 0 }}</strong></span>
                      <span>工具 <strong>{{ node.trace?.metrics.toolCalls ?? 0 }}</strong></span>
                      <span>Token <strong>{{ (node.trace?.estimatedPromptTokens ?? 0) + (node.trace?.estimatedCompletionTokens ?? 0) }}</strong></span>
                      <span>Evidence <strong>{{ node.evidence.length }}</strong></span>
                    </div>
                    <div v-if="node.task?.fencingToken" class="run-lease">lease token #{{ node.task.fencingToken }} · owner {{ node.task.claimedBy || 'released' }}<template v-if="node.task.claimUntil"> · 至 {{ dateTime(node.task.claimUntil) }}</template></div>
                    <p v-if="node.trace?.failureReason || node.task?.lastError" class="incident-error">{{ node.trace?.failureReason || node.task?.lastError }}</p>
                    <div v-if="node.evidence.length" class="run-evidence-chips"><span v-for="item in node.evidence" :key="item.evidenceId">{{ item.evidenceSubtype }}</span></div>
                    <button class="run-expand-button" type="button" @click="toggleRun(node.key)">{{ runExpanded(node.key) ? '收起执行细节' : '查看这个 Agent 在做什么' }}</button>
                  </div>
                </header>

                <div v-if="runExpanded(node.key)" class="run-detail-grid">
                  <section class="run-timeline-panel">
                    <div class="run-detail-title"><strong>执行时间线</strong><small>Runtime 持久化事件，已隐藏高频 Token Delta</small></div>
                    <ol v-if="significantEvents(node.trace).length" class="run-timeline">
                      <li v-for="event in significantEvents(node.trace)" :key="`${node.key}:${event.sequence}`" :class="event.eventType.toLowerCase()">
                        <i />
                        <div><header><strong>{{ eventLabel(event) }}</strong><time>{{ dateTime(event.occurredAt) }}</time></header><p>{{ compact(event.summary) }}</p><details v-if="Object.keys(event.payload).length"><summary>事件 payload</summary><pre>{{ JSON.stringify(event.payload, null, 2) }}</pre></details></div>
                      </li>
                    </ol>
                    <p v-else class="empty-line">Run 尚未产生持久化阶段事件，正在等待调度或关联 Trace。</p>
                  </section>

                  <section class="run-inspection-panel">
                    <div class="run-detail-title"><strong>输入与产物</strong><small>角色只能在冻结范围和白名单能力内工作</small></div>
                    <details v-if="node.trace?.question" open><summary>Agent 收到的任务</summary><pre>{{ node.trace.question }}</pre></details>
                    <details v-if="hasArtifact(node)"><summary>{{ node.runRole === 'COMMANDER' ? 'DelegationPlan' : node.runRole === 'REVIEWER' ? 'Assessment' : 'Task Output' }}</summary><pre>{{ JSON.stringify(runArtifact(node), null, 2) }}</pre></details>

                    <div class="tool-call-list">
                      <strong>工具调用</strong>
                      <article v-for="tool in toolSpans(node.trace)" :key="tool.spanId"><header><code>{{ tool.name }}</code><StatusBadge :value="tool.status" compact /></header><p>{{ compact(tool.summary) }} · {{ duration(tool.durationMs) }}</p><details><summary>工具属性</summary><pre>{{ JSON.stringify(tool.attributes, null, 2) }}</pre></details></article>
                      <p v-if="toolSpans(node.trace).length === 0" class="empty-line">该角色没有工具调用，或尚未进入工具阶段。</p>
                    </div>

                    <div class="agent-evidence-list">
                      <strong>该 Agent 产生的 Evidence</strong>
                      <article v-for="item in node.evidence" :key="item.evidenceId"><span>{{ item.evidenceSubtype }}</span><code>{{ item.evidenceId }}</code><small>{{ item.sourceSystem }} · {{ dateTime(item.observedAt) }}</small></article>
                      <p v-if="node.evidence.length === 0" class="empty-line">暂无 Evidence；Commander、Reviewer 和 Planner 默认不投影业务 FACT。</p>
                    </div>
                  </section>
                </div>
              </article>
              <p v-if="agentRunNodes.length === 0" class="empty-line">Commander 正在创建受限 DelegationPlan…</p>
            </div>
          </section>

          <section class="console-section">
            <div class="section-title"><span>03</span><div><strong>结构化事实证据</strong><small>仅成功只读 ToolExecution 可投影为 FACT</small></div></div>
            <div class="evidence-grid">
              <article v-for="item in aggregate.evidence" :key="item.evidenceId">
                <header><span>{{ item.evidenceClass }}</span><strong>{{ item.evidenceSubtype }}</strong></header>
                <p>{{ item.sourceSystem }} · {{ dateTime(item.observedAt) }}</p>
                <code>{{ item.evidenceId }}</code>
                <details><summary>查看 facts</summary><pre>{{ JSON.stringify(item.facts, null, 2) }}</pre></details>
              </article>
              <p v-if="aggregate.evidence.length === 0" class="empty-line">Specialist 正在读取业务事实…</p>
            </div>
          </section>

          <section class="console-section split-section">
            <div>
              <div class="section-title"><span>04</span><div><strong>Java 冲突检查</strong><small>跨 subtype 只通过显式 ComparisonRule 比较</small></div></div>
              <article v-for="item in conflicts" :key="item.eventId" class="conflict-card">
                <strong>{{ item.payload.severity }} · {{ item.payload.conflictType }}</strong>
                <p>{{ item.payload.metricKey }}</p>
                <code>{{ item.eventId }}</code>
              </article>
              <p v-if="conflicts.length === 0" class="empty-line">暂无可比较冲突</p>
            </div>
            <div>
              <div class="section-title"><span>05</span><div><strong>Reviewer 结论</strong><small>无有效引用的结论会被 Java Assembler 拒绝</small></div></div>
              <div v-if="Object.keys(aggregate.incident.assessment).length" class="assessment-card">
                <div class="assessment-outcome">{{ aggregate.incident.assessment.outcome }}<small>{{ aggregate.incident.assessment.riskLevel }}</small></div>
                <pre>{{ JSON.stringify(aggregate.incident.assessment, null, 2) }}</pre>
              </div>
              <p v-else class="empty-line">Reviewer 尚未完成证据评估</p>
            </div>
          </section>

          <section v-if="trace" class="trace-strip">
            <strong>模型 Run {{ trace.modelMetrics.modelRunCount ?? 0 }} 个</strong>
            <span>Prompt {{ trace.modelMetrics.promptTokens ?? 0 }}</span>
            <span>Completion {{ trace.modelMetrics.completionTokens ?? 0 }}</span>
            <span>Coordinator 模型调用 0</span>
            <span>点击上方 Agent 卡片可检查每个 Run 的完整执行过程</span>
          </section>

          <section v-if="terminal" class="console-section recovery-section">
            <div class="section-title"><span>06</span><div><strong>Recovery Planner</strong><small>模型只提出 ProposalRequest；Java 校验范围和证据；FlowOrder 生成不可变预演</small></div></div>
            <div v-if="!activeRecoveryPlan" class="recovery-start-card">
              <p>只有无开放高风险冲突、无证据缺口且结论为 ASSESSED 的事故才能进入恢复规划。</p>
              <textarea v-model="recoveryObjective" rows="3" :disabled="!canPlanRecovery" />
              <button class="start-button" type="button" :disabled="recoveryBusy || !canPlanRecovery" @click="startRecoveryPlan">
                {{ recoveryBusy ? 'Recovery Planner 正在规划…' : '生成受控恢复计划' }}
              </button>
              <small v-if="!canPlanRecovery">当前事故结论不允许自动生成恢复 Proposal，请保持人工处置。</small>
            </div>
            <article v-else class="recovery-plan-card">
              <header>
                <div><p class="eyebrow">RECOVERY PLAN</p><strong>{{ activeRecoveryPlan.planId }}</strong></div>
                <div class="plan-state"><StatusBadge :value="activeRecoveryPlan.status" compact /><span>{{ activeRecoveryPlan.outcome }}</span></div>
              </header>
              <div class="plan-meta"><code>plannerRunId {{ activeRecoveryPlan.plannerRunId || 'PENDING' }}</code><code>assessment {{ activeRecoveryPlan.assessmentDigest }}</code></div>
              <ul v-if="activeRecoveryPlan.validationErrors.length" class="validation-errors">
                <li v-for="message in activeRecoveryPlan.validationErrors" :key="message">{{ message }}</li>
              </ul>
              <div class="approval-form">
                <label>审批人<input v-model="reviewer" /></label>
                <label>审批意见<input v-model="approvalComment" /></label>
              </div>
              <div class="recovery-items">
                <article v-for="item in activeRecoveryPlan.items" :key="item.itemId">
                  <header><div><strong>{{ item.identifierType }} · {{ item.identifierValue }}</strong><small>{{ item.actionType }} · {{ item.status }}</small></div><StatusBadge :value="item.status" compact /></header>
                  <p>{{ item.suggestedReason }}</p>
                  <div v-if="item.proposal" class="proposal-grid">
                    <code>proposal {{ item.proposal.proposalId }} · v{{ item.proposal.proposalVersion }}</code>
                    <code>action {{ item.proposal.actionRequestId }}</code>
                    <span>有效期 {{ dateTime(item.proposal.expiresAt) }}</span>
                    <span>审批 {{ item.approvalStatus }} · 动作 {{ item.actionStatus }} · 结果 {{ item.caseOutcome }}</span>
                  </div>
                  <div v-if="item.proposal" class="preview-boxes">
                    <div><strong>执行影响</strong><p v-for="effect in item.proposal.effects" :key="effect">{{ effect }}</p></div>
                    <div><strong>审批警告</strong><p v-for="warning in item.proposal.warnings" :key="warning">{{ warning }}</p></div>
                  </div>
                   <p class="evidence-ref">证据引用：{{ item.evidenceIds.join(', ') }}</p>
                  <p v-if="item.fencingToken" class="evidence-ref">执行租约：token #{{ item.fencingToken }} · owner {{ item.executionOwner }} · 接管 {{ item.takeoverCount }} 次<span v-if="item.leaseUntil"> · 至 {{ dateTime(item.leaseUntil) }}</span></p>
                  <p v-if="item.lastError" class="incident-error">{{ item.lastError }}</p>
                  <div v-if="item.status === 'WAITING_APPROVAL'" class="decision-row">
                    <button type="button" :disabled="decidingItemId === item.itemId" @click="decideRecoveryItem(activeRecoveryPlan, item.itemId, true)">批准该 Proposal 并执行</button>
                    <button class="reject-button" type="button" :disabled="decidingItemId === item.itemId" @click="decideRecoveryItem(activeRecoveryPlan, item.itemId, false)">拒绝</button>
                  </div>
                </article>
                <p v-if="activeRecoveryPlan.items.length === 0" class="empty-line">Planner 正在生成并校验候选 Proposal…</p>
              </div>
            </article>
          </section>
        </template>
      </div>
    </section>
  </div>
</template>

<style scoped>
.incident-page { max-width: 1500px; margin: 0 auto; }
.incident-hero { padding: 25px 28px; border: 1px solid var(--line); border-radius: 16px 16px 0 0; display: flex; justify-content: space-between; gap: 24px; background: linear-gradient(135deg, #fff 55%, #f1f5ef); }
.incident-hero h2 { margin: 8px 0 10px; font-size: clamp(25px, 3vw, 38px); letter-spacing: -.045em; }
.incident-hero p:not(.eyebrow) { max-width: 850px; margin: 0; color: var(--muted); line-height: 1.75; }
.phase-chip { height: fit-content; padding: 8px 11px; border: 1px solid #b9d2bd; border-radius: 999px; color: #176b38; background: #eff8f0; font: 700 10px var(--mono); letter-spacing: .09em; }
.command-grid { display: grid; grid-template-columns: minmax(285px, 360px) minmax(0, 1fr); border: 1px solid var(--line); border-top: 0; border-radius: 0 0 16px 16px; min-height: 650px; }
.incident-form { padding: 23px; border-right: 1px solid var(--line); background: #f7f7f5; }
.incident-form label { display: grid; gap: 7px; margin-top: 15px; color: var(--muted); font-size: 12px; font-weight: 600; }
.incident-form input, .incident-form textarea { width: 100%; padding: 10px 11px; border: 1px solid var(--line); border-radius: 9px; background: #fff; color: var(--text); font: 12px/1.6 var(--mono); resize: vertical; }
.incident-form input:focus, .incident-form textarea:focus { outline: 2px solid rgba(37,99,235,.12); border-color: #7894d4; }
.start-button { width: 100%; margin-top: 18px; padding: 12px; border: 0; border-radius: 9px; color: #fff; background: #171817; font-weight: 700; cursor: pointer; }
.start-button:disabled { opacity: .55; cursor: wait; }
.incident-error { color: var(--red); font-size: 12px; line-height: 1.6; }
.incident-console { min-width: 0; padding: 25px; }
.empty-console { min-height: 570px; display: grid; place-content: center; justify-items: center; color: var(--muted); text-align: center; }
.empty-console span { width: 58px; height: 58px; display: grid; place-items: center; border: 1px solid var(--line); border-radius: 50%; color: #315a3a; font-size: 24px; }
.empty-console strong { margin-top: 15px; color: var(--text); }
.empty-console p { max-width: 400px; line-height: 1.7; }
.console-head { display: flex; justify-content: space-between; align-items: center; gap: 15px; }
.console-head strong { font: 700 14px var(--mono); color: #17492c; }
.progress-track { height: 4px; margin: 17px 0; overflow: hidden; border-radius: 99px; background: #e6e7e3; }
.progress-track i { display: block; height: 100%; background: #328357; transition: width .3s ease; }
.metric-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.metric-row div { padding: 11px 13px; border: 1px solid var(--line); border-radius: 9px; background: #fafaf9; }
.metric-row span, .metric-row strong { display: block; }
.metric-row span { color: var(--faint); font: 9px var(--mono); text-transform: uppercase; }
.metric-row strong { margin-top: 5px; font: 700 18px var(--mono); }
.console-section { margin-top: 28px; }
.section-title { display: flex; align-items: flex-start; gap: 10px; }
.section-title > span { color: #a4a5a0; font: 700 10px var(--mono); }
.section-title strong, .section-title small { display: block; }
.section-title strong { font-size: 14px; }
.section-title small { margin-top: 3px; color: var(--faint); font-size: 10px; line-height: 1.5; }
.agent-run-board { margin-top: 13px; display: grid; gap: 9px; }
.coordinator-node, .agent-run-card > header { display: flex; gap: 12px; }
.coordinator-node { padding: 14px; border: 1px solid #bfcbd9; border-radius: 11px; background: linear-gradient(135deg, #f8fbff, #f3f6f8); }
.run-node-icon { flex: 0 0 34px; width: 34px; height: 34px; display: grid; place-items: center; border-radius: 9px; color: #fff; background: #26384d; font: 800 12px var(--mono); box-shadow: 0 5px 14px rgba(28, 48, 69, .12); }
.run-node-icon.commander { background: #493d78; }
.run-node-icon.reviewer { background: #8a5429; }
.run-node-icon.recovery_planner { background: #8a5429; }
.run-node-icon[class*="specialist"] { background: #267047; }
.run-node-main { flex: 1; min-width: 0; }
.run-node-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.run-node-title strong, .run-node-title small { display: block; }
.run-node-title strong { font-size: 12px; }
.run-node-title small { max-width: 760px; margin-top: 4px; color: var(--muted); font-size: 10px; line-height: 1.55; }
.run-live-phase { display: flex; align-items: center; gap: 7px; margin-top: 9px; min-width: 0; color: #285a3b; font-size: 10px; }
.run-live-phase > i { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: #34a064; }
.run-live-phase > i.pulse { animation: run-pulse 1.2s ease-in-out infinite; }
.run-live-phase span { font-weight: 700; }
.run-live-phase code { margin-left: auto; max-width: 52%; overflow: hidden; text-overflow: ellipsis; color: var(--faint); font: 9px var(--mono); white-space: nowrap; }
@keyframes run-pulse { 0%, 100% { opacity: .35; box-shadow: 0 0 0 0 rgba(52,160,100,.25); } 50% { opacity: 1; box-shadow: 0 0 0 5px rgba(52,160,100,0); } }
.agent-branch-label { display: flex; align-items: center; justify-content: center; gap: 8px; color: var(--faint); font: 8px var(--mono); letter-spacing: .08em; text-transform: uppercase; }
.agent-branch-label span { width: 40px; height: 1px; background: var(--line); }
.agent-run-card { padding: 13px; overflow: hidden; border: 1px solid var(--line); border-left: 3px solid #6a846f; border-radius: 4px 11px 11px 4px; background: #f8faf7; transition: border-color .2s, box-shadow .2s, background .2s; }
.agent-run-card.expanded { border-color: #aebfb2; background: #fff; box-shadow: 0 12px 30px rgba(36, 56, 43, .07); }
.run-metrics { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 9px; }
.run-metrics span { padding: 4px 7px; border: 1px solid #dde4dc; border-radius: 999px; color: var(--muted); background: #fff; font: 8px var(--mono); }
.run-metrics strong { color: var(--text); }
.run-lease { margin-top: 7px; color: #6d776f; font: 8px/1.5 var(--mono); overflow-wrap: anywhere; }
.run-evidence-chips { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 8px; }
.run-evidence-chips span { padding: 3px 6px; border-radius: 5px; color: #246441; background: #eaf4ec; font: 700 8px var(--mono); }
.run-expand-button { margin-top: 10px; padding: 0; border: 0; color: #325d42; background: transparent; font: 700 9px var(--mono); cursor: pointer; }
.run-expand-button::before { content: '+ '; }
.agent-run-card.expanded .run-expand-button::before { content: '− '; }
.run-detail-grid { display: grid; grid-template-columns: minmax(260px, .9fr) minmax(320px, 1.1fr); gap: 14px; margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--line); }
.run-detail-title strong, .run-detail-title small { display: block; }
.run-detail-title strong { font-size: 11px; }
.run-detail-title small { margin-top: 3px; color: var(--faint); font-size: 8px; }
.run-timeline { position: relative; display: grid; gap: 0; margin: 11px 0 0; padding: 0; list-style: none; }
.run-timeline::before { content: ''; position: absolute; left: 5px; top: 7px; bottom: 7px; width: 1px; background: #d8ded8; }
.run-timeline li { position: relative; display: grid; grid-template-columns: 12px 1fr; gap: 8px; padding-bottom: 12px; }
.run-timeline li > i { position: relative; z-index: 1; width: 11px; height: 11px; margin-top: 2px; border: 3px solid #f8faf7; border-radius: 50%; background: #789080; }
.run-timeline li.tool_requested > i, .run-timeline li.tool_started > i, .run-timeline li.tool_completed > i { background: #28724a; }
.run-timeline li.run_failed > i, .run-timeline li.run_stopped > i { background: #a03d3d; }
.run-timeline header { display: flex; justify-content: space-between; gap: 8px; }
.run-timeline header strong { font-size: 9px; }
.run-timeline time { color: var(--faint); font: 8px var(--mono); }
.run-timeline p { margin: 3px 0 0; color: var(--muted); font: 8px/1.5 var(--mono); overflow-wrap: anywhere; }
.run-timeline details summary, .run-inspection-panel details summary { margin-top: 6px; }
.run-inspection-panel > details { margin-top: 10px; padding: 8px 9px; border: 1px solid var(--line); border-radius: 7px; background: #fafaf9; }
.run-inspection-panel pre { margin-bottom: 0; }
.tool-call-list, .agent-evidence-list { display: grid; gap: 7px; margin-top: 13px; }
.tool-call-list > strong, .agent-evidence-list > strong { font-size: 10px; }
.tool-call-list > article, .agent-evidence-list > article { padding: 8px 9px; border: 1px solid var(--line); border-radius: 7px; background: #fafbf9; }
.tool-call-list article header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.tool-call-list code, .agent-evidence-list code { color: #355b43; font: 8px var(--mono); overflow-wrap: anywhere; }
.tool-call-list p { margin: 5px 0 0; color: var(--muted); font-size: 9px; }
.agent-evidence-list article span, .agent-evidence-list article code, .agent-evidence-list article small { display: block; }
.agent-evidence-list article span { color: #247044; font: 700 9px var(--mono); }
.agent-evidence-list article code { margin-top: 4px; }
.agent-evidence-list article small { margin-top: 4px; color: var(--faint); font-size: 8px; }
.evidence-grid { margin-top: 12px; display: grid; grid-template-columns: repeat(auto-fit, minmax(225px, 1fr)); gap: 9px; }
.evidence-grid article { padding: 13px; border: 1px solid #cddccf; border-radius: 10px; background: #f8fbf8; min-width: 0; }
.evidence-grid header { display: flex; justify-content: space-between; gap: 8px; }
.evidence-grid header span { color: #267246; font: 700 9px var(--mono); }
.evidence-grid header strong { font: 700 10px var(--mono); }
.evidence-grid p { color: var(--muted); font-size: 10px; }
.evidence-grid code, .conflict-card code { color: var(--faint); font: 9px var(--mono); overflow-wrap: anywhere; }
details summary { margin-top: 10px; cursor: pointer; color: var(--muted); font-size: 10px; }
pre { max-height: 260px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: 10px/1.55 var(--mono); }
.split-section { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.conflict-card { margin-top: 10px; padding: 11px; border: 1px solid #e2c9bd; border-radius: 9px; background: #fff8f4; }
.conflict-card p { color: var(--muted); font-size: 11px; }
.assessment-card { margin-top: 10px; padding: 13px; border: 1px solid #d3cde7; border-radius: 10px; background: #faf8ff; }
.assessment-outcome { font: 800 18px var(--mono); color: #493d78; }
.assessment-outcome small { margin-left: 8px; font-size: 10px; }
.empty-line { color: var(--faint); font-size: 11px; }
.trace-strip { margin-top: 25px; padding: 12px; display: flex; flex-wrap: wrap; gap: 18px; border-top: 1px dashed var(--line); color: var(--muted); font: 10px var(--mono); }
.trace-strip strong { color: var(--text); }
.recovery-section { padding-top: 24px; border-top: 1px solid var(--line); }
.recovery-start-card, .recovery-plan-card { margin-top: 12px; padding: 15px; border: 1px solid #dacbad; border-radius: 12px; background: #fffaf1; }
.recovery-start-card textarea { width: 100%; padding: 9px; border: 1px solid var(--line); border-radius: 8px; resize: vertical; }
.recovery-start-card small { display: block; margin-top: 8px; color: var(--muted); }
.recovery-plan-card > header, .recovery-items article > header { display: flex; justify-content: space-between; gap: 12px; }
.plan-state { display: flex; align-items: center; gap: 8px; font: 10px var(--mono); }
.plan-meta { display: grid; gap: 4px; margin-top: 10px; color: var(--faint); font-size: 9px; overflow-wrap: anywhere; }
.validation-errors { color: var(--red); font-size: 11px; }
.approval-form { display: grid; grid-template-columns: 180px 1fr; gap: 8px; margin-top: 12px; }
.approval-form label { display: grid; gap: 4px; color: var(--muted); font-size: 10px; }
.approval-form input { padding: 8px; border: 1px solid var(--line); border-radius: 7px; }
.recovery-items { display: grid; gap: 10px; margin-top: 14px; }
.recovery-items > article { padding: 13px; border: 1px solid #ddd3bd; border-radius: 10px; background: #fff; }
.recovery-items small { display: block; margin-top: 4px; color: var(--muted); font: 9px var(--mono); }
.proposal-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 5px 12px; padding: 9px; background: #f7f6f2; font-size: 9px; overflow-wrap: anywhere; }
.preview-boxes { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 8px; }
.preview-boxes > div { padding: 9px; border: 1px solid var(--line); border-radius: 7px; }
.preview-boxes p, .evidence-ref { color: var(--muted); font-size: 10px; }
.decision-row { display: flex; gap: 8px; margin-top: 10px; }
.decision-row button { padding: 9px 11px; border: 0; border-radius: 7px; background: #1e6c3c; color: #fff; cursor: pointer; }
.decision-row .reject-button { background: #8b2e2e; }
@media (max-width: 1050px) { .command-grid { grid-template-columns: 1fr; } .incident-form { border-right: 0; border-bottom: 1px solid var(--line); } }
@media (max-width: 850px) { .run-detail-grid { grid-template-columns: 1fr; } }
@media (max-width: 700px) { .incident-hero { flex-direction: column; } .metric-row { grid-template-columns: repeat(2, 1fr); } .split-section { grid-template-columns: 1fr; } .run-node-title { display: grid; } .run-live-phase { flex-wrap: wrap; } .run-live-phase code { width: 100%; max-width: 100%; margin-left: 0; } }
</style>
