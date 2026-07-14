<script setup lang="ts">
import ModulePlaceholder from './ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder index="03" title="审批中心" description="查看高风险工具请求，作出并发安全的审批决策并恢复 Run。" :endpoints="['GET /api/agent/guardrails/approvals', 'POST /api/agent/guardrails/approvals/{id}/decide']" />
</template>
