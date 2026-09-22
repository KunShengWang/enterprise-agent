<script setup lang="ts">
import { retiredMessage } from '../utils/retiredBusiness'
</script>

<template>
  <section class="module-page">
    <h1>Incident Command 已退役</h1>
    <p role="status">{{ retiredMessage }}</p>
    <p>此旧链接不会启动任务。请从历史记录查找已保存的 Run 或审批记录。</p>
    <RouterLink to="/runs">查看 Run 历史</RouterLink>
    <RouterLink to="/approvals">查看审批历史</RouterLink>
  </section>
</template>
