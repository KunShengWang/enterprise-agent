<script setup lang="ts">
import ModulePlaceholder from './ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder index="02" title="Run 历史" description="读取持久化 Run、预算快照和事件时间线。" :endpoints="['GET /api/agent/runs', 'GET /api/agent/runs/{runId}/events']" />
</template>
