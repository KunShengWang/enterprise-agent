<script setup lang="ts">
import ModulePlaceholder from './ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder index="05" title="知识与记忆" description="独立实验 RAG 检索、语料统计、会话召回和用户画像。" :endpoints="['POST /api/agent/rag/search', 'GET /api/agent/memory/conversations/{id}/recall']" />
</template>
