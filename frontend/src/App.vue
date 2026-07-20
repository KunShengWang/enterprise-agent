<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import { agentApi } from './api/agent'

const route = useRoute()
const router = useRouter()
const sidebarOpen = ref(false)
const workbenchViewVersion = ref(0)
const backendOnline = ref(false)
const backendLabel = ref('检测中')

const pageTitle = computed(() => String(route.meta.title ?? route.name ?? 'Runtime Lab'))
const isWorkbench = computed(() => route.name === 'workbench')

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

async function createNewAgentTask() {
  sidebarOpen.value = false
  const nextConversationId = `workbench-${typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : Date.now()}`
  localStorage.setItem('unified-workbench-conversation', nextConversationId)
  if (route.name === 'workbench') {
    if (Object.keys(route.query).length > 0) {
      await router.replace({ name: 'workbench' })
    }
    workbenchViewVersion.value += 1
    return
  }

  workbenchViewVersion.value += 1
  await router.push({ name: 'workbench' })
}

onMounted(checkHealth)
</script>

<template>
  <div class="app-shell" :class="{ 'workbench-app-shell': isWorkbench }">
    <AppSidebar
      v-if="!isWorkbench"
      :open="sidebarOpen"
      @close="sidebarOpen = false"
      @new-task="createNewAgentTask"
    />

    <main class="app-main">
      <header v-if="!isWorkbench" class="topbar">
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

      <section class="page-frame" :class="{ 'workbench-page-frame': isWorkbench }">
        <RouterView v-slot="{ Component }">
          <component
            :is="Component"
            :key="route.name === 'workbench' ? `workbench-${workbenchViewVersion}` : String(route.name)"
          />
        </RouterView>
      </section>
    </main>
  </div>
</template>
