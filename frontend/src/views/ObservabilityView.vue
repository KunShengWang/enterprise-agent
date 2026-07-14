<script setup lang="ts">
import ModulePlaceholder from './ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder index="06" title="可观测性" description="查看 Trace、Replay、Eval 与 AgentOps 工程证据。" :endpoints="['GET /api/agent/traces', 'GET /api/agent/ops/summary', 'GET /api/agent/evals/reports']" />
</template>
