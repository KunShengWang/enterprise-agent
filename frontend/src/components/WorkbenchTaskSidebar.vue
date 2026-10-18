<script setup lang="ts">
import { executionTargetLabel } from '../utils/retiredBusiness'
import { RouterLink } from 'vue-router'
import { ref } from 'vue'
import {
  Activity, Blocks, BookOpen, ChevronDown, FlaskConical, History,
  Moon, PanelLeftClose, Plus, Search, Settings, ShieldCheck, ShoppingBag, Sun,
} from '@lucide/vue'
import type { ConversationHistoryItem, WorkItem } from '../types/workbench'
import { moreNav, settingsNav } from '../navigation'
import { applyTheme, currentTheme, type Theme } from '../theme'

defineProps<{ items: ConversationHistoryItem[]; selectedId: string; search: string }>()
const emit = defineEmits<{
  newTask: []
  select: [item: ConversationHistoryItem]
  close: []
  'update:search': [value: string]
}>()

function stateTone(item: WorkItem) {
  const value = `${item.controlState} ${item.executionState} ${item.outcome}`.toUpperCase()
  if (/(FAILED|CANCELLED|REJECTED|MANUAL_REVIEW|ABANDONED)/.test(value)) return 'failed'
  if (/(COMPLETED|CLOSED|RESOLVED)/.test(value)) return 'completed'
  if (/(WAITING|PAUSED)/.test(value)) return 'waiting'
  return 'active'
}

function targetLabel(target: string) { return executionTargetLabel(target) }

function relativeTime(value: string) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(value).getTime()) / 1000))
  if (seconds < 60) return '刚刚'
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(new Date(value))
}

/* ---- 导航收拢与主题（与 AppSidebar 共用 navigation.ts / theme.ts）---- */
const moreOpen = ref(false)
const theme = ref<Theme>(currentTheme())

const iconMap: Record<string, unknown> = {
  '/approvals': ShieldCheck,
  '/capabilities': Blocks,
  '/knowledge': BookOpen,
  '/observability': Activity,
  '/api-lab': FlaskConical,
  '/runs': History,
}

function iconFor(to: string) { return iconMap[to] ?? ShoppingBag }

function toggleTheme() {
  const next: Theme = theme.value === 'dark' ? 'light' : 'dark'
  theme.value = next
  applyTheme(next)
}
</script>

<template>
  <aside class="task-sidebar">
    <header class="task-sidebar-brand"><span><ShoppingBag :size="18" /></span><div><strong>Enterprise-Agent</strong><small>采购决策工作台</small></div><button type="button" aria-label="关闭任务栏" @click="emit('close')"><PanelLeftClose :size="18" /></button></header>
    <button class="task-new-button" type="button" @click="emit('newTask')"><Plus :size="18" />新建对话</button>
    <label class="task-search"><Search :size="16" /><input :value="search" aria-label="搜索对话" placeholder="搜索对话" @input="emit('update:search', ($event.target as HTMLInputElement).value)" /></label>
    <section class="task-history">
      <h2>最近对话</h2>
      <button v-for="item in items" :key="item.conversationId" type="button" :class="{ selected: item.conversationId === selectedId }" @click="emit('select', item)">
        <i :data-tone="stateTone(item.latestWorkItem)" /><div><strong>{{ item.title }}</strong><span><em>{{ targetLabel(item.latestWorkItem.activeExecutionTarget) }} · {{ item.workItemCount }} 轮</em><time>{{ relativeTime(item.updatedAt) }}</time></span></div>
      </button>
      <p v-if="!items.length">还没有对话</p>
    </section>
    <div class="task-product-nav">
      <button
        class="nav-more-toggle"
        type="button"
        :aria-expanded="moreOpen"
        aria-controls="task-sidebar-more"
        @click="moreOpen = !moreOpen"
      >
        <ChevronDown :size="15" class="nav-more-chevron" :class="{ 'is-open': moreOpen }" />
        <span>更多</span>
        <small>{{ moreNav.length }}</small>
      </button>
      <nav v-if="moreOpen" id="task-sidebar-more" class="task-nav-more" aria-label="更多页面">
        <RouterLink v-for="item in moreNav" :key="item.to" :to="item.to">
          <component :is="iconFor(item.to)" :size="17" />{{ item.label }}
        </RouterLink>
      </nav>
      <RouterLink :to="settingsNav.to" class="task-nav-fixed">
        <Settings :size="17" />{{ settingsNav.label }}
      </RouterLink>
      <button
        class="task-nav-fixed"
        type="button"
        :aria-label="theme === 'dark' ? '切换到浅色主题' : '切换到深色主题'"
        @click="toggleTheme"
      >
        <Moon v-if="theme === 'dark'" :size="17" />
        <Sun v-else :size="17" />
        {{ theme === 'dark' ? '深色' : '浅色' }}
      </button>
    </div>
  </aside>
</template>
