<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ value: string; compact?: boolean }>()

const normalized = computed(() => props.value?.toLowerCase().replaceAll('_', '-') || 'unknown')
const label = computed(() => {
  const labels: Record<string, string> = {
    running: '运行中',
    pause_requested: '暂停中',
    'pause-requested': '暂停中',
    paused: '已暂停',
    completed: '已完成',
    waiting_approval: '等待审批',
    'waiting-approval': '等待审批',
    failed: '失败',
    blocked: '已拦截',
    rejected: '已拒绝',
    manual_review: '人工复核',
    'manual-review': '人工复核',
    requested: '待处理',
    approved: '已批准',
    expired: '已过期',
    cancelled: '已取消',
    needs_clarification: '待澄清',
    'needs-clarification': '待澄清',
    transport_interrupted: '连接中断',
    'transport-interrupted': '连接中断',
  }
  return labels[props.value?.toLowerCase()] ?? labels[normalized.value] ?? props.value ?? '未知'
})
</script>

<template>
  <span class="status-badge" :class="[`status-${normalized}`, { compact }]">
    <i />{{ label }}
  </span>
</template>
