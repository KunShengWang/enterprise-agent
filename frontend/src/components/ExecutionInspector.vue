<script setup lang="ts">
import { computed, ref } from 'vue'
import StatusBadge from './StatusBadge.vue'
import EventPayloadDrawer from './EventPayloadDrawer.vue'
import type { ApprovalRecord } from '../types/agent'
import type { PublicPresentation, WorkEvent, WorkExecutionTree, WorkItemBudget, WorkItemDetail } from '../types/workbench'
import { diagnosticEvents, projectorLagMs, projectActivity, projectTools, type ActivityFilter } from '../utils/inspectorProjection'

const props = defineProps<{
  detail: WorkItemDetail
  tree: WorkExecutionTree | null
  events: WorkEvent[]
  budget: WorkItemBudget | null
  approval: ApprovalRecord | null
  inspectorPresentations: PublicPresentation[]
  deltaStreamState: string
  presentationStreamState: string
  workCursor: number
  runCursor: number
  presentationCursor: number
  lastEventAt: string
  gap: boolean
  syncError: string
  reconnectCount?: number
  finalAnswerAt?: string
  busy?: boolean
}>()

const emit = defineEmits<{ command: [value: 'pause' | 'resume' | 'cancel'] }>()
type InspectorTab = 'activity' | 'agents' | 'tools' | 'evidence' | 'diagnostics'
const activeTab = ref<InspectorTab>('activity')
const filter = ref<ActivityFilter>('all')
const query = ref('')
const selectedEvent = ref<WorkEvent | null>(null)
const copied = ref(false)
const tabs: Array<{ id: InspectorTab; label: string }> = [
  { id: 'activity', label: 'Activity' }, { id: 'agents', label: 'Agents' },
  { id: 'tools', label: 'Tools' }, { id: 'evidence', label: 'Evidence' },
  { id: 'diagnostics', label: 'Diagnostics' },
]
const filters: Array<{ id: ActivityFilter; label: string }> = [
  { id: 'all', label: '全部' }, { id: 'error', label: '错误' }, { id: 'tool', label: '工具' },
  { id: 'model', label: '模型' }, { id: 'approval', label: '审批' }, { id: 'recovery', label: '恢复' },
]

const activityGroups = computed(() => projectActivity(props.events, filter.value, query.value))
const tools = computed(() => projectTools(props.inspectorPresentations))
const diagnostics = computed(() => diagnosticEvents(props.events))
const projectorLag = computed(() => projectorLagMs(props.events))
const agentCount = computed(() => (props.tree?.agents.length ?? 0) + (props.tree?.coordinator ? 1 : 0))
const runState = computed(() => props.tree?.agents.find(agent => agent.runId === props.detail.workItem.activeRunId)?.status
  || props.tree?.agents[0]?.status || props.detail.workItem.executionState)

const elapsed = computed(() => {
  const start = new Date(props.detail.workItem.createdAt).getTime()
  const terminalAt = props.detail.workItem.completedAt || props.finalAnswerAt
  const end = terminalAt ? new Date(terminalAt).getTime() : Date.now()
  const seconds = Math.max(0, Math.round((end - start) / 1000))
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
})

function compactId(value?: string) {
  if (!value) return '—'
  return value.length > 24 ? `${value.slice(0, 10)}…${value.slice(-8)}` : value
}
function roleLabel(role: string) {
  const labels: Record<string, string> = { COMMANDER: 'Commander', REVIEWER: 'Reviewer', RECOVERY_PLANNER: 'Planner', GENERAL_AGENT: 'General Agent', ORDERCARE_CASE: 'OrderCare Agent' }
  return labels[role] ?? role.match(/^SPECIALIST:([^:]+)/)?.[1] ?? role
}
function number(value?: number) { return new Intl.NumberFormat('zh-CN').format(value ?? 0) }
function eventStatus(event: WorkEvent) {
  const value = `${event.eventType} ${event.phase}`.toUpperCase()
  if (/(FAILED|ERROR|REJECTED|EXPIRED)/.test(value)) return 'failed'
  if (/(COMPLETED|SUCCEEDED|SETTLED|CLOSED)/.test(value)) return 'completed'
  if (/(STARTED|RUNNING|CLAIMED|REQUESTED)/.test(value)) return 'active'
  return 'neutral'
}
function eventAttempt(event: WorkEvent) { return event.payload?.attempt ?? event.payload?.attemptNo ?? '—' }
function eventDuration(event: WorkEvent) { const value = event.payload?.durationMs; return typeof value === 'number' ? `${value} ms` : '—' }
async function copy(value: string) {
  await navigator.clipboard.writeText(value)
  copied.value = true
  window.setTimeout(() => { copied.value = false }, 1200)
}
</script>

<template>
  <aside class="execution-inspector">
    <header class="inspector-header">
      <div><span>TECHNICAL EXECUTION</span><strong>执行检查器</strong></div>
      <StatusBadge :value="detail.workItem.executionState" compact />
    </header>
    <div class="inspector-controls">
      <button type="button" aria-label="暂停任务" :disabled="busy" @click="emit('command', 'pause')">Ⅱ</button>
      <button type="button" aria-label="从检查点继续" :disabled="busy" @click="emit('command', 'resume')">▶</button>
      <button type="button" aria-label="取消任务" :disabled="busy" @click="emit('command', 'cancel')">■</button>
      <span v-if="copied">已复制</span><span v-else :data-state="deltaStreamState">{{ deltaStreamState }}</span>
    </div>

    <section class="inspector-fixed-summary">
      <div class="inspector-state-grid">
        <div><span>Control</span><strong>{{ detail.workItem.controlState }}</strong></div>
        <div><span>Execution</span><strong>{{ detail.workItem.executionState }}</strong></div>
        <div><span>Outcome</span><strong>{{ detail.workItem.outcome }}</strong></div>
        <div><span>Target state</span><strong>{{ runState }}</strong></div>
      </div>
      <div class="inspector-metric-strip">
        <span><b>{{ elapsed }}</b>耗时</span>
        <span><b>{{ number((tree?.metrics.promptTokens ?? 0) + (tree?.metrics.completionTokens ?? 0)) }}</b>Token</span>
        <span><b>{{ tools.length }}</b>Tools</span>
        <span><b>{{ agentCount }}</b>Agents</span>
        <span><b>{{ projectorLag }} ms</b>Lag</span>
      </div>
      <details class="inspector-identifiers"><summary>标识与预算</summary><dl>
        <dt>WorkItem</dt><dd :title="detail.workItem.workItemId">{{ compactId(detail.workItem.workItemId) }}</dd>
        <dt>Run</dt><dd>{{ compactId(detail.workItem.activeRunId) }}</dd>
        <dt>Incident</dt><dd>{{ compactId(detail.workItem.activeIncidentId) }}</dd>
        <dt>Plan</dt><dd>{{ compactId(detail.workItem.activeRecoveryPlanId) }}</dd>
        <dt>Budget</dt><dd>{{ budget ? `${number(budget.consumed.tokens)} / ${number(budget.maximum.tokens)} tokens` : '—' }}</dd>
      </dl></details>
    </section>

    <nav class="inspector-tabs" aria-label="执行详情">
      <button v-for="tab in tabs" :key="tab.id" type="button" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}</button>
    </nav>

    <div class="inspector-content">
      <section v-if="activeTab === 'activity'" class="inspector-activity">
        <div class="activity-toolbar"><input v-model="query" aria-label="搜索执行事件" placeholder="搜索事件" /><div><button v-for="item in filters" :key="item.id" type="button" :class="{ active: filter === item.id }" @click="filter = item.id">{{ item.label }}</button></div></div>
        <div class="activity-groups">
          <details v-for="group in activityGroups" :key="group.id" open>
            <summary><span>{{ group.label }}</span><small>{{ group.events.length }}</small></summary>
            <ol><li v-for="event in group.events" :key="event.eventId" :data-status="eventStatus(event)"><i /><button type="button" @click="selectedEvent = event"><strong>{{ event.summary || event.phase || event.eventType }}</strong><small>#{{ event.sequence }} · {{ event.sourceType || 'WORK_ITEM' }} · {{ compactId(event.sourceId) }}</small><span>{{ event.sourceCreatedAt ? new Date(event.sourceCreatedAt).toLocaleTimeString('zh-CN') : '—' }} · Attempt {{ eventAttempt(event) }} · {{ eventDuration(event) }}</span></button></li></ol>
          </details>
        </div>
        <p v-if="!activityGroups.length" class="inspector-empty">没有符合条件的执行事件。</p>
      </section>

      <section v-else-if="activeTab === 'agents'" class="inspector-agents">
        <article v-if="tree?.coordinator"><header><span>C</span><div><strong>{{ tree.coordinator.label }}</strong><small>Coordinator · synthetic · {{ tree.coordinator.status }}</small></div></header><p>{{ tree.coordinator.span.summary }}</p></article>
        <article v-for="agent in tree?.agents" :key="agent.nodeId" :data-status="agent.status.toLowerCase()">
          <header><span>{{ roleLabel(agent.role).slice(0, 1) }}</span><div><strong>{{ roleLabel(agent.role) }}</strong><small>{{ agent.status }} · Attempt {{ agent.attempt }}/{{ agent.maxAttempts }}</small></div></header>
          <p>{{ agent.objective }}</p><div class="agent-metric-line"><span>{{ agent.metrics.durationMs }} ms</span><span>模型 {{ agent.metrics.modelCalls }}</span><span>工具 {{ agent.metrics.toolCalls }}</span><span>Token {{ number(agent.metrics.promptTokens + agent.metrics.completionTokens) }}</span></div>
          <p v-if="agent.error" class="agent-error">{{ agent.error }}</p>
        </article>
        <p v-if="!tree?.agents.length && !tree?.coordinator" class="inspector-empty">单 Agent 尚未生成执行树节点。</p>
      </section>

      <section v-else-if="activeTab === 'tools'" class="inspector-tools">
        <article v-for="tool in tools" :key="tool.toolCallId" :data-status="tool.item.status.toLowerCase()"><header><div><strong>{{ tool.item.detail.tool?.displayName }}</strong><small>{{ tool.item.detail.tool?.toolName }} · {{ compactId(tool.toolCallId) }}</small></div><StatusBadge :value="tool.item.status" compact /></header><p>{{ tool.item.detail.tool?.actionSummary }}</p><dl><dt>Attempt</dt><dd>{{ tool.item.detail.tool?.attemptLabel }}</dd><dt>Duration</dt><dd>{{ tool.item.detail.tool?.durationMs ?? '—' }} ms</dd><dt>Result count</dt><dd>{{ tool.item.detail.tool?.resultCount ?? '—' }}</dd><dt>Source</dt><dd>{{ tool.item.sourceType }} · {{ compactId(tool.item.sourceId) }}</dd></dl><details><summary>公开参数与安全结果</summary><pre>{{ JSON.stringify({ arguments: tool.item.detail.tool?.publicArguments, summary: tool.item.detail.tool?.resultSummary }, null, 2) }}</pre></details></article>
        <p v-if="!tools.length" class="inspector-empty">当前任务没有公开 Tool activity。</p>
      </section>

      <section v-else-if="activeTab === 'evidence'" class="inspector-evidence">
        <section><h3>Evidence <span>{{ tree?.evidence.length ?? 0 }}</span></h3><article v-for="item in tree?.evidence" :key="item.evidenceId"><strong>{{ item.evidenceSubtype }}</strong><small>{{ item.evidenceClass }} · {{ compactId(item.evidenceId) }}</small></article></section>
        <section><h3>Conflict <span>{{ tree?.conflicts.length ?? 0 }}</span></h3><article v-for="item in tree?.conflicts" :key="item.conflictId"><strong>{{ item.severity }} · {{ item.conflictType }}</strong><small>{{ item.metricKey }} · {{ item.status }}</small></article></section>
        <section v-if="tree && Object.keys(tree.assessment).length"><h3>Assessment</h3><pre>{{ JSON.stringify(tree.assessment, null, 2) }}</pre></section>
        <section v-if="detail.preview"><h3>Proposal / Preview</h3><article><strong>{{ detail.preview.status }} · v{{ detail.preview.previewVersion }}</strong><small>{{ compactId(detail.preview.previewId) }}</small></article></section>
        <section v-if="tree?.recoveryPlans.length"><h3>Recovery Plan <span>{{ tree.recoveryPlans.length }}</span></h3><article v-for="plan in tree.recoveryPlans" :key="plan.planId"><strong>{{ plan.status }} · {{ plan.outcome }}</strong><small>{{ plan.items.length }} 个处置项</small></article></section>
        <section v-if="approval"><h3>Approval</h3><article><strong>{{ approval.status }} · {{ approval.toolCallRequest.toolName }}</strong><small>{{ approval.reason }}</small></article></section>
        <p v-if="!tree?.evidence.length && !tree?.conflicts.length && !tree?.recoveryPlans.length && !detail.preview && !approval" class="inspector-empty">当前任务没有证据、Proposal 或审批记录。</p>
      </section>

      <section v-else class="inspector-diagnostics">
        <dl><dt>Presentation SSE</dt><dd>{{ presentationStreamState }}</dd><dt>Delta SSE</dt><dd>{{ deltaStreamState }}</dd><dt>Reconnect</dt><dd>{{ reconnectCount ?? 0 }}</dd><dt>Work cursor</dt><dd>{{ workCursor }}</dd><dt>Run cursor</dt><dd>{{ runCursor }}</dd><dt>Presentation cursor</dt><dd>{{ presentationCursor }}</dd><dt>Last event</dt><dd>{{ lastEventAt || '—' }}</dd><dt>Gap</dt><dd>{{ gap ? 'DETECTED' : 'none' }}</dd><dt>Projector lag</dt><dd>{{ projectorLag }} ms</dd></dl>
        <p v-if="syncError" class="diagnostic-error">{{ syncError }}</p>
        <h3>恢复与故障事件</h3><button v-for="event in diagnostics" :key="event.eventId" type="button" @click="selectedEvent = event"><strong>{{ event.summary || event.phase }}</strong><span>#{{ event.sequence }} · {{ eventStatus(event) }}</span></button>
        <p v-if="!diagnostics.length" class="inspector-empty">当前没有 UNKNOWN、reconciliation、lease、fencing、budget exhausted 或 recovery 事件。</p>
      </section>
    </div>
    <EventPayloadDrawer :event="selectedEvent" :work-item-id="detail.workItem.workItemId" @close="selectedEvent = null" @copy="copy" />
  </aside>
</template>
