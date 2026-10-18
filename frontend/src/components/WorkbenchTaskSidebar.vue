<script setup lang="ts">
import { executionTargetLabel } from '../utils/retiredBusiness'
import { PanelLeftClose, Plus, Search, ShoppingBag } from '@lucide/vue'
import type { ConversationHistoryItem, WorkItem } from '../types/workbench'
import SidebarNav from './SidebarNav.vue'

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
      <!-- 「更多」+ 设置 + 主题切换与次级页共用 SidebarNav，避免两处模板漂移 -->
      <SidebarNav />
    </div>
  </aside>
</template>
