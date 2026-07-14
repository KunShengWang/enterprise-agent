<script setup lang="ts">
defineProps<{
  index: string
  title: string
  description: string
  endpoints: string[]
}>()
</script>

<template>
  <section class="placeholder-card">
    <div class="placeholder-index">{{ index }}</div>
    <div>
      <p class="eyebrow">MODULE PREVIEW</p>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
      <div class="endpoint-list">
        <code v-for="endpoint in endpoints" :key="endpoint">{{ endpoint }}</code>
      </div>
    </div>
  </section>
</template>
