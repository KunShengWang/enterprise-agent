<script setup lang="ts">
import ModulePlaceholder from './ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder
    index="01"
    title="Agent 运行台"
    description="通过 POST SSE 连接观察统一 Agent Runtime 的实时事件，并同步投影最终回答。"
    :endpoints="['POST /api/agent/runs/events', 'POST /api/agent/runs/{runId}/cancel']"
  />
</template>
