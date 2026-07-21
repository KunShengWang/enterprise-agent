<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ConversationTurnView, PresentationLocator } from '../types/conversation'
import ConversationItemRenderer from './ConversationItemRenderer.vue'

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
const expanded = ref(!terminal.value)
watch(() => props.selected, selected => { if (selected && !terminal.value) expanded.value = true })
watch(terminal, value => { if (!value) expanded.value = true })

const visibleEntries = computed(() => props.view.entries.filter(item => expanded.value
  || ['USER_MESSAGE', 'FINAL_ANSWER', 'ERROR'].includes(item.type)))

function duration(value: number) {
  const seconds = Math.max(0, Math.round(value / 1000))
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}
</script>

<template>
  <section class="conversation-turn" :class="{ selected, collapsed: !expanded }" :data-turn-id="view.turn.turnId">
    <button class="turn-heading" type="button" @click="emit('select', view.turn.turnId)">
      <span>第 {{ index + 1 }} 轮</span>
      <small>{{ view.turn.executionTarget || 'Routing' }} · {{ view.turn.executionState }}</small>
      <i :data-state="terminal ? 'terminal' : 'active'" />
    </button>
    <div class="turn-content">
      <ConversationItemRenderer v-for="item in visibleEntries" :key="item.id" :item="item"
        :busy="busy" :reviewer="reviewer" :decision-reason="decisionReason"
        @update:reviewer="emit('update:reviewer', $event)"
        @update:decision-reason="emit('update:decisionReason', $event)"
        @confirm-preview="emit('confirmPreview', $event)"
        @decide-approval="emit('decideApproval', $event)" @copy="emit('copy', $event)"
        @retry="emit('retry')" @diagnostics="emit('diagnostics')"
        @supply-input="emit('supplyInput')"
        @locate-presentations="emit('locatePresentations', $event)" />
    </div>
    <button v-if="terminal && view.stepCount" class="turn-execution-toggle" type="button" @click="expanded = !expanded; emit('select', view.turn.turnId)">
      执行记录 · {{ view.stepCount }} 个步骤 · {{ duration(view.durationMs) }}<template v-if="view.agentCount"> · {{ view.agentCount }} Agents</template>
      <span>{{ expanded ? '收起' : '展开' }}</span>
    </button>
  </section>
</template>
