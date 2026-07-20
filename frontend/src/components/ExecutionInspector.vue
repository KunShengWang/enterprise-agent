<script setup lang="ts">
import { computed, ref } from 'vue'
import StatusBadge from './StatusBadge.vue'
import type { ApprovalRecord } from '../types/agent'
import type { PublicPresentation, WorkEvent, WorkExecutionTree, WorkItemBudget, WorkItemDetail } from '../types/workbench'

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
  finalAnswerAt?: string
  busy?: boolean
}>()

const emit = defineEmits<{ command: [value: 'pause' | 'resume' | 'cancel'] }>()
const activeTab = ref<'overview' | 'activity' | 'agents' | 'evidence'>('overview')

const tabs = [
  { id: 'overview' as const, label: '概览' },
  { id: 'activity' as const, label: '活动' },
  { id: 'agents' as const, label: 'Agents' },
  { id: 'evidence' as const, label: '证据' },
]

const activityGroups = computed(() => {
  const groups = [
    { id: 'intake', label: '任务接收', matches: ['WORK_ITEM_CREATED'] },
    { id: 'route', label: '路由', matches: ['ROUTING_', 'DISPATCH_READY', 'DISPATCH_STARTED', 'EXECUTION_DISPATCHED'] },
    { id: 'context', label: '上下文', matches: ['CONTEXT_'] },
    { id: 'model', label: '模型', matches: ['MODEL_', 'RUN_STARTED'] },
    { id: 'tool', label: '工具', matches: ['TOOL_', 'POLICY_'] },
    { id: 'approval', label: '确认与审批', matches: ['APPROVAL', 'CONFIRMATION', 'PAUSE', 'RESUME'] },
    { id: 'result', label: '结果', matches: ['COMPLETED', 'FAILED', 'CANCELLED', 'CLOSED'] },
  ]
  const grouped = new Map(groups.map(group => [group.id, [] as WorkEvent[]]))
  props.events.forEach(event => {
    const value = `${event.eventType} ${event.phase ?? ''}`.toUpperCase()
    const groupId = value.includes('WORK_ITEM_CREATED') ? 'intake'
      : /(APPROVAL|CONFIRMATION|PAUSE|RESUME)/.test(value) ? 'approval'
        : /(TOOL_|POLICY_)/.test(value) ? 'tool'
          : value.includes('CONTEXT_') ? 'context'
            : /(MODEL_|RUN_STARTED)/.test(value) ? 'model'
              : /(RUN_COMPLETED|RUN_FAILED|WORK_ITEM_CANCELLED|WORK_ITEM_ABANDONED|CLOSED)/.test(value) ? 'result'
                : /(ROUTING_|DISPATCH_READY|DISPATCH_STARTED|EXECUTION_DISPATCHED)/.test(value) ? 'route'
                  : 'result'
    grouped.get(groupId)?.push(event)
  })
  return groups.map(group => ({ ...group, events: grouped.get(group.id) ?? [] })).filter(group => group.events.length)
})

const elapsed = computed(() => {
  const start = new Date(props.detail.workItem.createdAt).getTime()
  const terminalEvent = [...props.events].reverse().find(event => {
    const value = `${event.eventType} ${event.phase ?? ''}`.toUpperCase()
    return /(RUN_COMPLETED|RUN_FAILED|WORK_ITEM_CANCELLED|WORK_ITEM_ABANDONED|INCIDENT_COMPLETED|PLAN_COMPLETED)/.test(value)
  })
  const terminalAt = props.detail.workItem.completedAt
    || terminalEvent?.sourceCreatedAt
    || terminalEvent?.projectedAt
    || props.finalAnswerAt
  const end = terminalAt ? new Date(terminalAt).getTime() : Date.now()
  const seconds = Math.max(0, Math.round((end - start) / 1000))
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
})

function compactId(value?: string) {
  if (!value) return '—'
  return value.length > 24 ? `${value.slice(0, 10)}…${value.slice(-8)}` : value
}

function roleLabel(role: string) {
  const labels: Record<string, string> = {
    COMMANDER: 'Commander', REVIEWER: 'Reviewer', RECOVERY_PLANNER: 'Planner',
    GENERAL_AGENT: 'General Agent', ORDERCARE_CASE: 'OrderCare Agent',
  }
  const specialist = role.match(/^SPECIALIST:([^:]+)/)?.[1]
  return labels[role] ?? specialist ?? role
}

function number(value?: number) {
  return new Intl.NumberFormat('zh-CN').format(value ?? 0)
}
</script>

<template>
  <aside class="execution-inspector">
    <header class="inspector-header">
      <div><span>EXECUTION</span><strong>执行检查器</strong></div>
      <StatusBadge :value="detail.workItem.executionState" compact />
    </header>

    <div class="inspector-controls">
      <button type="button" title="暂停任务" :disabled="busy" @click="emit('command', 'pause')">Ⅱ</button>
      <button type="button" title="从检查点继续" :disabled="busy" @click="emit('command', 'resume')">▶</button>
      <button type="button" title="取消任务" :disabled="busy" @click="emit('command', 'cancel')">■</button>
      <span :data-state="deltaStreamState">{{ deltaStreamState }}</span>
    </div>

    <nav class="inspector-tabs" aria-label="执行详情">
      <button v-for="tab in tabs" :key="tab.id" type="button" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id">{{ tab.label }}</button>
    </nav>

    <div class="inspector-content">
      <section v-if="activeTab === 'overview'" class="inspector-overview">
        <div class="overview-primary"><span>执行目标</span><strong>{{ detail.workItem.activeExecutionTarget || '正在路由' }}</strong><small>{{ detail.workItem.controlState }} · {{ detail.workItem.outcome }}</small></div>
        <div class="overview-metrics">
          <div><span>耗时</span><strong>{{ elapsed }}</strong></div>
          <div><span>Token</span><strong>{{ number((tree?.metrics.promptTokens ?? 0) + (tree?.metrics.completionTokens ?? 0)) }}</strong></div>
          <div><span>工具</span><strong>{{ tree?.metrics.toolCalls ?? 0 }}</strong></div>
          <div><span>Agents</span><strong>{{ tree?.metrics.agentNodes ?? 0 }}</strong></div>
        </div>
        <section class="inspector-section">
          <h3>标识</h3>
          <dl>
            <dt>WorkItem</dt><dd :title="detail.workItem.workItemId">{{ compactId(detail.workItem.workItemId) }}</dd>
            <dt>Run</dt><dd :title="detail.workItem.activeRunId">{{ compactId(detail.workItem.activeRunId) }}</dd>
            <dt>Incident</dt><dd :title="detail.workItem.activeIncidentId">{{ compactId(detail.workItem.activeIncidentId) }}</dd>
            <dt>Plan</dt><dd :title="detail.workItem.activeRecoveryPlanId">{{ compactId(detail.workItem.activeRecoveryPlanId) }}</dd>
          </dl>
        </section>
        <section class="inspector-section">
          <h3>传输状态</h3>
          <dl>
            <dt>Presentation SSE</dt><dd>{{ presentationStreamState }}</dd>
            <dt>Delta SSE</dt><dd>{{ deltaStreamState }}</dd>
            <dt>Presentation cursor</dt><dd>{{ presentationCursor }}</dd>
            <dt>Work / Run cursor</dt><dd>{{ workCursor }} / {{ runCursor }}</dd>
            <dt>最近事件</dt><dd>{{ lastEventAt ? new Date(lastEventAt).toLocaleTimeString('zh-CN') : '—' }}</dd>
            <dt>Gap</dt><dd>{{ gap ? '是' : '否' }}</dd>
          </dl>
          <p v-if="syncError" class="agent-error">{{ syncError }}</p>
        </section>
        <section class="inspector-section">
          <h3>预算</h3>
          <template v-if="budget">
            <div class="budget-progress"><i :style="{ width: `${Math.min(100, budget.maximum.tokens ? budget.consumed.tokens / budget.maximum.tokens * 100 : 0)}%` }" /></div>
            <dl><dt>Token</dt><dd>{{ number(budget.consumed.tokens) }} / {{ number(budget.maximum.tokens) }}</dd><dt>模型调用</dt><dd>{{ budget.consumed.modelCalls }} / {{ budget.maximum.modelCalls }}</dd><dt>工具调用</dt><dd>{{ budget.consumed.toolCalls }} / {{ budget.maximum.toolCalls }}</dd></dl>
          </template>
          <p v-else class="inspector-empty">当前执行目标没有独立预算投影。</p>
        </section>
      </section>

      <section v-else-if="activeTab === 'activity'" class="activity-groups">
        <details v-if="inspectorPresentations.length">
          <summary><span>Presentation 投影</span><small>{{ inspectorPresentations.length }}</small></summary>
          <ol><li v-for="item in inspectorPresentations" :key="item.presentationId"><i /><div><strong>{{ item.title }}</strong><small>#{{ item.sequence }} · {{ item.visibility }} · {{ item.sourceType }}</small><details><summary>完整 Presentation</summary><pre>{{ JSON.stringify(item, null, 2) }}</pre></details></div></li></ol>
        </details>
        <details v-for="group in activityGroups" :key="group.id">
          <summary><span>{{ group.label }}</span><small>{{ group.events.length }}</small></summary>
          <ol><li v-for="event in group.events" :key="event.eventId"><i /><div><strong>{{ event.summary }}</strong><small>#{{ event.sequence }} · {{ event.phase || event.eventType }}</small><details><summary>完整 WorkEvent</summary><pre>{{ JSON.stringify(event, null, 2) }}</pre></details></div></li></ol>
        </details>
        <p v-if="!activityGroups.length" class="inspector-empty">还没有执行活动。</p>
      </section>

      <section v-else-if="activeTab === 'agents'" class="inspector-agents">
        <article v-if="tree?.coordinator"><header><span>C</span><div><strong>{{ tree.coordinator.label }}</strong><small>Coordinator · synthetic</small></div></header><p>{{ tree.coordinator.span.summary }}</p></article>
        <article v-for="agent in tree?.agents" :key="agent.nodeId" :data-status="agent.status.toLowerCase()">
          <header><span>{{ roleLabel(agent.role).slice(0, 1) }}</span><div><strong>{{ roleLabel(agent.role) }}</strong><small>{{ agent.status }} · Attempt {{ agent.attempt }}/{{ agent.maxAttempts }}</small></div></header>
          <p>{{ agent.objective }}</p>
          <div class="agent-metric-line"><span>模型 {{ agent.metrics.modelCalls }}</span><span>工具 {{ agent.metrics.toolCalls }}</span><span>Token {{ number(agent.metrics.promptTokens + agent.metrics.completionTokens) }}</span></div>
          <p v-if="agent.error" class="agent-error">{{ agent.error }}</p>
        </article>
        <p v-if="!tree?.agents.length && !tree?.coordinator" class="inspector-empty">当前还没有 Agent 节点。</p>
      </section>

      <section v-else class="inspector-evidence">
        <section><h3>Evidence <span>{{ tree?.evidence.length ?? 0 }}</span></h3><article v-for="item in tree?.evidence" :key="item.evidenceId"><strong>{{ item.evidenceSubtype }}</strong><small>{{ item.evidenceClass }} · {{ compactId(item.evidenceId) }}</small></article></section>
        <section><h3>Conflict <span>{{ tree?.conflicts.length ?? 0 }}</span></h3><article v-for="item in tree?.conflicts" :key="item.conflictId"><strong>{{ item.severity }} · {{ item.conflictType }}</strong><small>{{ item.metricKey }}</small></article></section>
        <section v-if="tree && Object.keys(tree.assessment).length"><h3>Assessment</h3><pre>{{ JSON.stringify(tree.assessment, null, 2) }}</pre></section>
        <section v-if="tree?.recoveryPlans.length"><h3>Recovery Plan <span>{{ tree.recoveryPlans.length }}</span></h3><article v-for="plan in tree.recoveryPlans" :key="plan.planId"><strong>{{ plan.status }} · {{ plan.outcome }}</strong><small>{{ plan.items.length }} 个处置项</small></article></section>
        <section v-if="approval"><h3>Approval</h3><article><strong>{{ approval.status }} · {{ approval.toolCallRequest.toolName }}</strong><small>{{ approval.reason }}</small></article></section>
        <p v-if="!tree?.evidence.length && !tree?.conflicts.length && !tree?.recoveryPlans.length && !approval" class="inspector-empty">当前任务没有证据或审批记录。</p>
      </section>
    </div>
  </aside>
</template>
