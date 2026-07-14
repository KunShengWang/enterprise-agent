<script setup lang="ts">
import { computed, ref } from 'vue'

const props = withDefaults(defineProps<{ value: unknown; collapsed?: boolean; label?: string }>(), {
  collapsed: true,
  label: '查看结构化数据',
})

const open = ref(!props.collapsed)
const json = computed(() => JSON.stringify(props.value ?? {}, null, 2))
</script>

<template>
  <div class="json-viewer">
    <button type="button" @click="open = !open">
      <span>{{ open ? '−' : '+' }}</span>{{ label }}
    </button>
    <pre v-if="open"><code>{{ json }}</code></pre>
  </div>
</template>
