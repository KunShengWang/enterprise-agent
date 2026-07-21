<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { ConversationHistoryItem, WorkItem } from '../types/workbench'

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

function targetLabel(target: string) {
  return ({ GENERAL_AGENT: 'General', ORDERCARE_CASE: 'OrderCare', INCIDENT_INVESTIGATION: 'Incident',
    INCIDENT_RECOVERY_PLAN: 'Planner' } as Record<string, string>)[target] ?? target ?? 'Routing'
}

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
    <header class="task-sidebar-brand"><span>A</span><div><strong>Agent Workbench</strong><small>Enterprise Agent</small></div><button type="button" aria-label="关闭任务栏" @click="emit('close')">×</button></header>
    <button class="task-new-button" type="button" @click="emit('newTask')"><span>＋</span>新建对话</button>
    <label class="task-search"><span>⌕</span><input :value="search" aria-label="搜索对话" placeholder="搜索对话" @input="emit('update:search', ($event.target as HTMLInputElement).value)" /></label>
    <section class="task-history">
      <h2>最近对话</h2>
      <button v-for="item in items" :key="item.conversationId" type="button" :class="{ selected: item.conversationId === selectedId }" @click="emit('select', item)">
        <i :data-tone="stateTone(item.latestWorkItem)" /><div><strong>{{ item.title }}</strong><span><em>{{ targetLabel(item.latestWorkItem.activeExecutionTarget) }} · {{ item.workItemCount }} 轮</em><time>{{ relativeTime(item.updatedAt) }}</time></span></div>
      </button>
      <p v-if="!items.length">还没有对话</p>
    </section>
    <nav class="task-product-nav" aria-label="产品导航">
      <RouterLink to="/approvals"><span>✓</span>审批中心</RouterLink>
      <RouterLink to="/incident-command"><span>△</span>事故调查</RouterLink>
      <RouterLink to="/capabilities"><span>⌘</span>能力地图</RouterLink>
      <RouterLink to="/knowledge"><span>◇</span>知识与记忆</RouterLink>
      <RouterLink to="/observability"><span>⌁</span>可观测性</RouterLink>
    </nav>
  </aside>
</template>
