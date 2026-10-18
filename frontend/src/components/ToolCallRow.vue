<script setup lang="ts">
import { computed, ref } from 'vue'
import { LoaderCircle, CircleCheck, CircleAlert, Clock3, ChevronRight, CircleHelp } from '@lucide/vue'
import ToolCallDetails from './ToolCallDetails.vue'
import { firstLine, publicToolLabel, toolStateLabel } from '../utils/toolPresentation'
import type { ConversationItem } from '../types/conversation'
const props = defineProps<{ item: ConversationItem }>()
const emit = defineEmits<{ diagnostics: [] }>()
const expanded = ref(false)
const label = computed(() => publicToolLabel(props.item.tool!))
const statusLabel = computed(() => toolStateLabel(props.item.status))
const icon = computed(() => ({ active: LoaderCircle, completed: CircleCheck, failed: CircleAlert,
  waiting: Clock3, pending: Clock3, unresolved: CircleHelp })[props.item.status])

/**
 * 状态图标已经表达了「已完成 / 执行中」，再写一遍状态名是重复信息。
 * - failed / waiting：显示原因的第一行（用户需要知道为什么）
 * - unresolved：只显示简短状态名，长句解释留在展开详情（一行放不下）
 * - 其余：不显示文字
 */
const summary = computed(() => {
  if (props.item.status === 'unresolved') return statusLabel.value
  if (props.item.status === 'failed' || props.item.status === 'waiting') {
    return firstLine(props.item.tool?.summary || statusLabel.value)
  }
  return ''
})
</script>
<template>
  <div class="tool-call-row" :data-status="item.status" :data-call-id="item.tool?.callId">
    <button
      class="tool-row-toggle"
      type="button"
      :aria-expanded="expanded"
      :aria-label="`${label} · ${item.tool?.toolName} · ${statusLabel}${summary ? ' · ' + summary : ''}`"
      :title="item.tool?.toolName"
      @click="expanded = !expanded"
    >
      <component :is="icon" class="tool-row-state-icon" :size="16" aria-hidden="true" />
      <strong>{{ label }}</strong>
      <span v-if="summary" class="tool-row-summary">{{ summary }}</span>
      <span v-if="item.tool?.durationMs != null" class="tool-row-duration">{{ item.tool.durationMs }}ms</span>
      <ChevronRight class="tool-row-chevron" :size="14" aria-hidden="true" />
    </button>
    <ToolCallDetails v-if="expanded" :item="item" @diagnostics="emit('diagnostics')" />
  </div>
</template>
