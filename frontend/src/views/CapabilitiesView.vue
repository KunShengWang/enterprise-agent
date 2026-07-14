<script setup lang="ts">
import ModulePlaceholder from './ModulePlaceholder.vue'
</script>

<template>
  <ModulePlaceholder index="04" title="能力地图" description="对照 Tool、Skill 定义与真实工具执行记录。" :endpoints="['GET /api/agent/tools', 'GET /api/agent/skills']" />
</template>
