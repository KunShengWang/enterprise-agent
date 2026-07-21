<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { WorkEvent } from '../types/workbench'
import { sanitizeInspectorPayload } from '../utils/inspectorProjection'

const props = defineProps<{ event: WorkEvent | null; workItemId: string }>()
const emit = defineEmits<{ close: []; copy: [value: string] }>()
const drawer = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
let previousFocus: HTMLElement | null = null
const safePayload = computed(() => JSON.stringify(sanitizeInspectorPayload(props.event?.payload ?? {}), null, 2))
const fields = computed(() => props.event ? [
  ['eventId', props.event.eventId], ['workItemId', props.event.workItemId || props.workItemId],
  ['sourceType', props.event.sourceType], ['sourceId', props.event.sourceId],
  ['sourceSequence', props.event.sourceSequence], ['workSequence', props.event.sequence],
  ['correlationId', props.event.correlationId], ['causationId', props.event.causationId],
  ['runId', props.event.sourceType === 'AGENT_RUN' ? props.event.sourceId : props.event.payload?.runId],
  ['traceId', props.event.payload?.traceId],
  ['occurredAt', props.event.sourceCreatedAt], ['projectedAt', props.event.projectedAt],
] : [])

function handleKeydown(event: KeyboardEvent) {
  if (!props.event) return
  if (event.key === 'Escape') { emit('close'); return }
  if (event.key !== 'Tab' || !drawer.value) return
  const focusable = [...drawer.value.querySelectorAll<HTMLElement>('button, [href], input, textarea, select, [tabindex]:not([tabindex="-1"])')]
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable.at(-1)!
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

watch(() => props.event, async event => {
  if (event) {
    previousFocus = document.activeElement as HTMLElement | null
    window.addEventListener('keydown', handleKeydown)
    await nextTick()
    closeButton.value?.focus()
  } else {
    window.removeEventListener('keydown', handleKeydown)
    previousFocus?.focus()
    previousFocus = null
  }
})
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <div v-if="event" class="event-drawer-backdrop" @click.self="emit('close')">
    <aside ref="drawer" class="event-payload-drawer" role="dialog" aria-modal="true" aria-label="事件详情">
      <header><div><span>WORK EVENT</span><strong>事件详情</strong></div><button ref="closeButton" type="button" aria-label="关闭事件详情" @click="emit('close')">×</button></header>
      <dl><template v-for="field in fields" :key="field[0]"><dt>{{ field[0] }}</dt><dd><span>{{ field[1] ?? '—' }}</span><button v-if="field[1]" type="button" :aria-label="`复制 ${field[0]}`" @click="emit('copy', String(field[1]))">复制</button></dd></template></dl>
      <section><h3>安全 Payload</h3><pre>{{ safePayload }}</pre></section>
    </aside>
  </div>
</template>
