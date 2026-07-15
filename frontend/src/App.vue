<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import { agentApi } from './api/agent'

const route = useRoute()
const sidebarOpen = ref(false)
const backendOnline = ref(false)
const backendLabel = ref('检测中')

const pageTitle = computed(() => String(route.meta.title ?? route.name ?? 'Runtime Lab'))

async function checkHealth() {
  try {
    const health = await agentApi.health()
    backendOnline.value = true
    backendLabel.value = `${health.name} · ${health.stage}`
  } catch {
    backendOnline.value = false
    backendLabel.value = '后端未连接'
  }
}

onMounted(checkHealth)
</script>

<template>
  <div class="app-shell">
    <AppSidebar :open="sidebarOpen" @close="sidebarOpen = false" />

    <main class="app-main">
      <header class="topbar">
        <button class="icon-button mobile-menu" type="button" aria-label="打开导航" @click="sidebarOpen = true">
          ☰
        </button>
        <div class="topbar-title">
          <h1>{{ pageTitle }}</h1>
          <span>Enterprise Agent</span>
        </div>
        <button class="health-pill" type="button" title="重新检测后端" @click="checkHealth">
          <span class="status-dot" :class="backendOnline ? 'is-online' : 'is-offline'" />
          {{ backendLabel }}
        </button>
      </header>

      <section class="page-frame">
        <RouterView />
      </section>
    </main>
  </div>
</template>
