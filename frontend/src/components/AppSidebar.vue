<script setup lang="ts">
import { RouterLink } from 'vue-router'

defineProps<{ open: boolean }>()
defineEmits<{ close: []; newTask: [] }>()

const navigation = [
  { to: '/', icon: '✦', label: 'Agent 运行台', hint: '对话与实时执行' },
  { to: '/runs', icon: '◷', label: 'Run 历史', hint: '状态与事件回放' },
  { to: '/approvals', icon: '✓', label: '审批中心', hint: 'HITL 决策与恢复' },
  { to: '/incident-command', icon: '△', label: '事故调查', hint: '只读 Multi-Agent 指挥台' },
  { to: '/capabilities', icon: '⌘', label: '能力地图', hint: 'Tool 与 Skill' },
  { to: '/knowledge', icon: '◇', label: '知识与记忆', hint: 'RAG 与 Memory' },
  { to: '/observability', icon: '⌁', label: '可观测性', hint: 'Trace · Eval · Ops' },
  { to: '/api-lab', icon: '›_', label: '接口实验室', hint: '完整 API 地图' },
]
</script>

<template>
  <div v-if="open" class="sidebar-scrim" @click="$emit('close')" />
  <aside class="sidebar" :class="{ 'is-open': open }">
    <div class="brand-block">
      <div class="brand-mark">✦</div>
      <div>
        <strong>Agent Studio</strong>
        <span>Runtime 学习工作区</span>
      </div>
      <button class="icon-button sidebar-close" type="button" aria-label="关闭导航" @click="$emit('close')">×</button>
    </div>

    <button class="new-task-button" type="button" @click="$emit('newTask')">
      <span>＋</span>
      新建 Agent 任务
    </button>

    <nav class="nav-list" aria-label="主导航">
      <RouterLink v-for="item in navigation" :key="item.to" :to="item.to" @click="$emit('close')">
        <span class="nav-code">{{ item.icon }}</span>
        <span class="nav-copy">
          <strong>{{ item.label }}</strong>
          <small>{{ item.hint }}</small>
        </span>
      </RouterLink>
    </nav>

    <div class="sidebar-note">
      <span class="pulse-marker" />
      <div>
        <strong>Runtime 已连接</strong>
        <p>正文保留模型回答，执行详情使用等宽字体记录真实事件。</p>
      </div>
    </div>
  </aside>
</template>
