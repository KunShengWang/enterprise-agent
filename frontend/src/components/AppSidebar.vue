<script setup lang="ts">
import { RouterLink } from 'vue-router'

defineProps<{ open: boolean }>()
defineEmits<{ close: [] }>()

const navigation = [
  { to: '/', code: '01', label: 'Agent 运行台', hint: 'SSE 与执行阶段' },
  { to: '/runs', code: '02', label: 'Run 历史', hint: '状态与事件回放' },
  { to: '/approvals', code: '03', label: '审批中心', hint: 'HITL 决策与恢复' },
  { to: '/capabilities', code: '04', label: '能力地图', hint: 'Tool 与 Skill' },
  { to: '/knowledge', code: '05', label: '知识与记忆', hint: 'RAG 与 Memory' },
  { to: '/observability', code: '06', label: '可观测性', hint: 'Trace · Eval · Ops' },
  { to: '/api-lab', code: '07', label: '接口实验室', hint: '完整 API 地图' },
]
</script>

<template>
  <div v-if="open" class="sidebar-scrim" @click="$emit('close')" />
  <aside class="sidebar" :class="{ 'is-open': open }">
    <div class="brand-block">
      <div class="brand-mark">EA</div>
      <div>
        <strong>Runtime Lab</strong>
        <span>Agent 执行学习台</span>
      </div>
      <button class="icon-button sidebar-close" type="button" aria-label="关闭导航" @click="$emit('close')">×</button>
    </div>

    <nav class="nav-list" aria-label="主导航">
      <RouterLink v-for="item in navigation" :key="item.to" :to="item.to" @click="$emit('close')">
        <span class="nav-code">{{ item.code }}</span>
        <span class="nav-copy">
          <strong>{{ item.label }}</strong>
          <small>{{ item.hint }}</small>
        </span>
        <span class="nav-arrow">↗</span>
      </RouterLink>
    </nav>

    <div class="sidebar-note">
      <span class="pulse-marker" />
      <div>
        <strong>学习提示</strong>
        <p>先从运行台观察一条完整事件流，再到 Run 历史对照数据库事实源。</p>
      </div>
    </div>
  </aside>
</template>
