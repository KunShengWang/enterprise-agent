<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ConversationTurnView, PresentationLocator } from '../types/conversation'
import ConversationItemRenderer from './ConversationItemRenderer.vue'
import ProcurementFacts from './ProcurementFacts.vue'
import { LoaderCircle, CircleCheck, CircleAlert, Clock3, ChevronDown, ChevronUp } from '@lucide/vue'
import { deriveExecutionPhase } from '../utils/procurementPresentation'

const props = defineProps<{
  view: ConversationTurnView
  index: number
  selected: boolean
  busy: boolean
  reviewer: string
  decisionReason: string
}>()
const emit = defineEmits<{
  select: [turnId: string]
  confirmPreview: [approved: boolean]
  decideApproval: [approved: boolean]
  copy: [content: string]
  retry: []
  diagnostics: []
  supplyInput: []
  locatePresentations: [locator: PresentationLocator]
  'update:reviewer': [value: string]
  'update:decisionReason': [value: string]
}>()

const terminal = computed(() => ['COMPLETED', 'FAILED', 'CANCELLED', 'UNKNOWN']
  .includes(props.view.turn.executionState.toUpperCase())
  || ['CLOSED', 'ABANDONED'].includes(props.view.turn.controlState.toUpperCase()))
// 运行中默认展开：任务执行时就能看到工具调用与阶段活动，不必每次点开；
// 进入终态后默认收起（历史记录保持紧凑，需要时手动展开）。
const expanded = ref(!terminal.value)
watch(() => props.view.turn.turnId, () => { expanded.value = !terminal.value })
const phase = computed(() => deriveExecutionPhase(props.view.turn, props.view.entries))
const tools = computed(() => props.view.entries.filter(item => item.tool))
const recentToolIds = computed(() => new Set(tools.value.slice(-3).map(item => item.id)))

const visibleEntries = computed(() => props.view.entries.filter(item => {
  if (item.type === 'FINAL_ANSWER' && !item.content && phase.value.status === 'waiting') return false
  return expanded.value
  || ['USER_MESSAGE', 'FINAL_ANSWER', 'ERROR', 'APPROVAL_REQUEST', 'ROUTE_PREVIEW'].includes(item.type)
  || item.presentationKind === 'WAITING_FOR_USER'
  || !terminal.value && item.tool && (['active', 'waiting', 'failed'].includes(item.status) || recentToolIds.value.has(item.id))
}))
const phaseIcon = computed(() => ({ active: LoaderCircle, completed: CircleCheck, failed: CircleAlert, waiting: Clock3, pending: Clock3, unresolved: Clock3 }[phase.value.status]))

function duration(value: number) {
  const seconds = Math.max(0, Math.round(value / 1000))
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

/**
 * 首字延迟期间，用户看到的是模型思考时间。没有计量就没有「还在动」的证据，
 * 只能靠猜——这里按任务的真实开始时间（view.turn.startedAt）每秒刷新一次。
 */
const now = ref(Date.now())
let elapsedTimer: number | null = null
onMounted(() => {
  elapsedTimer = window.setInterval(() => { now.value = Date.now() }, 500)
})
onBeforeUnmount(() => {
  if (elapsedTimer !== null) window.clearInterval(elapsedTimer)
  elapsedTimer = null
})

const elapsedMs = computed(() => {
  if (terminal.value) return 0
  const startedAt = Date.parse(props.view.turn.createdAt ?? '')
  if (!Number.isFinite(startedAt)) return 0
  return Math.max(0, now.value - startedAt)
})
</script>

<template>
  <section class="conversation-turn" :class="{ selected, collapsed: !expanded }" :data-turn-id="view.turn.turnId">
    <button class="turn-heading" type="button" @click="emit('select', view.turn.turnId)">
      <span>第 {{ index + 1 }} 轮</span>
      <small>{{ phase.label }}</small>
    </button>
    <div class="turn-content">
      <ConversationItemRenderer v-for="item in visibleEntries.filter(entry => entry.type === 'USER_MESSAGE')" :key="item.id" :item="item" :busy="busy" :reviewer="reviewer" :decision-reason="decisionReason" />
      <div class="execution-phase" role="status" :data-status="phase.status">
        <component :is="phaseIcon" :size="18" />
        <div><strong>{{ phase.label }}</strong><p v-if="phase.detail">{{ phase.detail }}</p></div>
        <span v-if="elapsedMs > 0" class="phase-timer" :title="`任务开始于 ${view.turn.createdAt}`">{{ duration(elapsedMs) }}</span>
      </div>
      <ProcurementFacts :entries="view.entries" />
      <button v-if="tools.length || view.stepCount" class="turn-execution-toggle" type="button" :aria-expanded="expanded" @click="expanded = !expanded">
        <span>执行记录 · {{ tools.length }} 次工具调用<template v-if="view.stepCount"> · {{ view.stepCount }} 项活动</template><template v-if="terminal && view.durationMs"> · {{ duration(view.durationMs) }}</template></span><component :is="expanded ? ChevronUp : ChevronDown" :size="16" />
      </button>
      <ConversationItemRenderer v-for="item in visibleEntries.filter(entry => entry.type !== 'USER_MESSAGE')" :key="item.id" :item="item"
        :busy="busy" :reviewer="reviewer" :decision-reason="decisionReason"
        @update:reviewer="emit('update:reviewer', $event)"
        @update:decision-reason="emit('update:decisionReason', $event)"
        @confirm-preview="emit('confirmPreview', $event)"
        @decide-approval="emit('decideApproval', $event)" @copy="emit('copy', $event)"
        @retry="emit('retry')" @diagnostics="emit('diagnostics')"
        @supply-input="emit('supplyInput')"
        @locate-presentations="emit('locatePresentations', $event)" />
    </div>
  </section>
</template>
