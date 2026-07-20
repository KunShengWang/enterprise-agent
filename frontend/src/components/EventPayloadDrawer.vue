<script setup lang="ts">
import { computed } from 'vue'
import type { WorkEvent } from '../types/workbench'
import { sanitizeInspectorPayload } from '../utils/inspectorProjection'

const props = defineProps<{ event: WorkEvent | null; workItemId: string }>()
const emit = defineEmits<{ close: []; copy: [value: string] }>()
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
</script>

<template>
  <div v-if="event" class="event-drawer-backdrop" @click.self="emit('close')">
    <aside class="event-payload-drawer" role="dialog" aria-modal="true" aria-label="事件详情">
      <header><div><span>WORK EVENT</span><strong>事件详情</strong></div><button type="button" aria-label="关闭事件详情" @click="emit('close')">×</button></header>
      <dl><template v-for="field in fields" :key="field[0]"><dt>{{ field[0] }}</dt><dd><span>{{ field[1] ?? '—' }}</span><button v-if="field[1]" type="button" :aria-label="`复制 ${field[0]}`" @click="emit('copy', String(field[1]))">复制</button></dd></template></dl>
      <section><h3>安全 Payload</h3><pre>{{ safePayload }}</pre></section>
    </aside>
  </div>
</template>
