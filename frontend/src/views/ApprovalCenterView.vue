<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { agentApi } from '../api/agent'
import JsonViewer from '../components/JsonViewer.vue'
import PageIntro from '../components/PageIntro.vue'
import StatusBadge from '../components/StatusBadge.vue'
import type { ApprovalRecord } from '../types/agent'

const approvals = ref<ApprovalRecord[]>([])
const router = useRouter()
const selected = ref<ApprovalRecord | null>(null)
const filter = ref('ALL')
const loading = ref(false)
const error = ref('')

const filtered = computed(() => approvals.value.filter((item) => filter.value === 'ALL' || item.status === filter.value))
const pendingCount = computed(() => approvals.value.filter((item) => item.status === 'REQUESTED').length)

function dateTime(value: string | null) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value)) : '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    approvals.value = await agentApi.approvals(100)
    if (selected.value) {
      selected.value = approvals.value.find((item) => item.approvalId === selected.value?.approvalId) ?? null
    }
  } catch (reasonValue) {
    error.value = reasonValue instanceof Error ? reasonValue.message : '加载审批记录失败'
  } finally {
    loading.value = false
  }
}

function openInWorkbench() {
  if (!selected.value) return
  if (!selected.value.runId) {
    error.value = '这条旧审批记录没有关联 Run ID，无法进入运行台。'
    return
  }
  void router.push({ name: 'runtime', query: { runId: selected.value.runId } })
}

onMounted(load)
</script>

<template>
  <div class="module-page">
    <PageIntro
      kicker="HUMAN IN THE LOOP"
      title="把高风险工具停在执行之前"
      description="审批中心用于检索待办和查看审计记录；实际批准、拒绝与流式恢复统一回到对应 Run 的 Agent 运行台完成。"
      :endpoints="['GET /api/agent/guardrails/approvals', 'GET /api/agent/guardrails/approvals/{approvalId}', 'GET /api/agent/runs/{runId}']"
    >
      <div class="counter-chip"><strong>{{ pendingCount }}</strong><span>待审批</span></div>
      <button class="secondary-button" type="button" :disabled="loading" @click="load">刷新</button>
    </PageIntro>

    <p v-if="error" class="inline-error">{{ error }}</p>

    <div class="approval-layout">
      <section class="panel approval-list-panel">
        <div class="filter-chips">
          <button v-for="value in ['ALL', 'REQUESTED', 'APPROVED', 'REJECTED', 'EXPIRED']" :key="value" type="button" :class="{ active: filter === value }" @click="filter = value">
            {{ value }}
          </button>
        </div>
        <button
          v-for="approval in filtered"
          :key="approval.approvalId"
          type="button"
          class="approval-record"
          :class="{ selected: selected?.approvalId === approval.approvalId }"
          @click="selected = approval"
        >
          <div><StatusBadge :value="approval.status" compact /><time>{{ dateTime(approval.createdAt) }}</time></div>
          <strong>{{ approval.toolCallRequest?.toolName || '未知工具' }}</strong>
          <span>{{ approval.reason }}</span>
          <code>{{ approval.approvalId }}</code>
        </button>
        <div v-if="!filtered.length" class="compact-empty">{{ loading ? '正在读取审批…' : '当前筛选条件下没有记录' }}</div>
      </section>

      <section class="panel approval-detail-panel">
        <div v-if="!selected" class="detail-empty">
          <span>⌁</span><strong>选择一条审批记录</strong><p>查看工具参数、有效期以及最终决策。</p>
        </div>
        <template v-else>
          <div class="detail-title">
            <div><p class="eyebrow">APPROVAL RECORD</p><h3>{{ selected.toolCallRequest?.toolName }}</h3></div>
            <StatusBadge :value="selected.status" />
          </div>
          <div class="approval-meta-grid">
            <div><span>Approval ID</span><code>{{ selected.approvalId }}</code></div>
            <div><span>Run ID</span><code>{{ selected.runId || '旧数据未关联' }}</code></div>
            <div><span>创建时间</span><strong>{{ dateTime(selected.createdAt) }}</strong></div>
            <div><span>过期时间</span><strong>{{ dateTime(selected.expiresAt) }}</strong></div>
          </div>
          <div class="policy-reason"><span>为什么需要审批</span><p>{{ selected.reason }}</p></div>
          <JsonViewer :value="selected.toolCallRequest" :collapsed="false" label="ToolCall 参数" />

          <div v-if="selected.status === 'REQUESTED'" class="decision-form">
            <p>这条 Run 尚未执行高风险工具。进入运行台后，可以在同一条事件时间线中完成审批并继续执行。</p>
            <div class="action-row">
              <button class="primary-button" type="button" @click="openInWorkbench">进入运行台处理</button>
            </div>
          </div>
          <div v-else class="decision-result">
            <span>最终决策</span>
            <strong>{{ selected.status }}</strong>
            <p>{{ selected.reviewer || '—' }} · {{ selected.decisionReason || '未填写理由' }}</p>
            <button class="secondary-button" type="button" @click="openInWorkbench">在运行台查看完整 Run</button>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>
