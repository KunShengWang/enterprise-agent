<script setup lang="ts">
defineProps<{
  kicker: string
  title: string
  description: string
  endpoints?: string[]
}>()
</script>

<template>
  <header class="page-intro">
    <div>
      <p class="eyebrow">{{ kicker }}</p>
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
      <div v-if="endpoints?.length" class="intro-endpoints">
        <code v-for="endpoint in endpoints" :key="endpoint">{{ endpoint }}</code>
      </div>
    </div>
    <div class="page-intro-actions"><slot /></div>
  </header>
</template>
