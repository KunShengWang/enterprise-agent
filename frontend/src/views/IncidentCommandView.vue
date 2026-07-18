<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { incidentApi } from '../api/incident'
import StatusBadge from '../components/StatusBadge.vue'
import type { IncidentAggregate, IncidentStreamItem, IncidentTrace } from '../types/incident'

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
let stream: EventSource | null = null
let refreshTimer: number | null = null

const terminal = computed(() => ['ASSESSED', 'PARTIAL', 'MANUAL_REVIEW', 'FAILED', 'CANCELLED']
  .includes(aggregate.value?.incident.status ?? ''))
const conflicts = computed(() => aggregate.value?.events.filter(event => event.eventType === 'EVIDENCE_CONFLICT_DETECTED') ?? [])
const progress = computed(() => {
  const status = aggregate.value?.incident.status ?? 'CREATED'
  const steps = ['CREATED', 'PLANNING', 'INVESTIGATING', 'CHECKING_CONSISTENCY', 'REVIEWING']
  const terminalIndex = terminal.value ? steps.length : Math.max(0, steps.indexOf(status))
  return Math.round((terminalIndex / steps.length) * 100)
})

function lines(value: string) {
  return [...new Set(value.split(/[\s,，;；]+/).map(item => item.trim()).filter(Boolean))]
}

function dateTime(value?: string) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value)) : '—'
}

async function start() {
  closeStream()
  busy.value = true
  error.value = ''
  aggregate.value = null
  trace.value = null
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
    if (terminal.value) {
      trace.value = await incidentApi.trace(incidentId.value)
      closeStream()
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '调查状态刷新失败'
  }
}

function openStream() {
  if (!incidentId.value) return
  stream = new EventSource(incidentApi.streamUrl(incidentId.value))
  stream.onmessage = event => {
    const item = JSON.parse(event.data) as IncidentStreamItem
    if (item.type === 'EVENT') scheduleRefresh()
  }
  stream.onerror = () => scheduleRefresh()
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
  if (refreshTimer !== null) window.clearTimeout(refreshTimer)
  refreshTimer = null
}

onBeforeUnmount(closeStream)
</script>

<template>
  <div class="incident-page">
    <header class="incident-hero">
      <div>
        <p class="eyebrow">ORDERCARE INCIDENT COMMAND · PHASE 1</p>
        <h2>异常订单事故调查与证据指挥台</h2>
        <p>Commander 只做受限分工，Specialist 只调用白名单只读能力，Java 完成事实投影与冲突检查，Reviewer 只能引用已落库证据。</p>
      </div>
      <div class="phase-chip">READ ONLY</div>
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
            <div class="section-title"><span>02</span><div><strong>Agent Run 拓扑</strong><small>每个角色独立统计，Coordinator 是 synthetic span</small></div></div>
            <div class="task-list">
              <article v-for="task in aggregate.tasks" :key="task.taskId">
                <div><strong>{{ task.role }}</strong><small>{{ task.objective }}</small></div>
                <div class="task-state"><StatusBadge :value="task.status" compact /><code>{{ task.childRunId || 'RUN PENDING' }}</code></div>
              </article>
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
            <strong>模型调用 {{ trace.modelMetrics.modelRunCount ?? 0 }} 次</strong>
            <span>Prompt {{ trace.modelMetrics.promptTokens ?? 0 }}</span>
            <span>Completion {{ trace.modelMetrics.completionTokens ?? 0 }}</span>
            <span>Coordinator 模型调用 0</span>
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
.task-list { margin-top: 12px; display: grid; gap: 7px; }
.task-list article { padding: 11px 12px; border-left: 3px solid #6a846f; background: #f6f8f5; display: flex; justify-content: space-between; gap: 14px; }
.task-list strong, .task-list small { display: block; }
.task-list small { margin-top: 4px; color: var(--muted); line-height: 1.5; }
.task-state { text-align: right; }
.task-state code { display: block; margin-top: 6px; color: var(--faint); font: 9px var(--mono); }
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
@media (max-width: 1050px) { .command-grid { grid-template-columns: 1fr; } .incident-form { border-right: 0; border-bottom: 1px solid var(--line); } }
@media (max-width: 700px) { .incident-hero { flex-direction: column; } .metric-row { grid-template-columns: repeat(2, 1fr); } .split-section { grid-template-columns: 1fr; } .task-list article { display: block; } .task-state { margin-top: 9px; text-align: left; } }
</style>
