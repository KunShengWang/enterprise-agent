<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { agentApi } from '../api/agent'
import JsonViewer from '../components/JsonViewer.vue'
import PageIntro from '../components/PageIntro.vue'
import StatusBadge from '../components/StatusBadge.vue'
import type { ApprovalRecord } from '../types/agent'

const approvals = ref<ApprovalRecord[]>([])
const selected = ref<ApprovalRecord | null>(null)
const filter = ref('ALL')
const reviewer = ref('student-reviewer')
const reason = ref('已核对工具参数、权限边界和副作用范围')
const resumeAfterApprove = ref(true)
const loading = ref(false)
const deciding = ref(false)
const error = ref('')
const resultMessage = ref('')

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

async function decide(approved: boolean) {
  if (!selected.value) return
  deciding.value = true
  error.value = ''
  resultMessage.value = ''
  try {
    const decision = await agentApi.decideApproval(
      selected.value.approvalId,
      approved,
      reviewer.value.trim() || 'learning-console',
      reason.value.trim(),
    )
    resultMessage.value = `审批已原子更新为 ${decision.status}`
    if (approved && resumeAfterApprove.value && selected.value.runId) {
      const response = await agentApi.resumeRun(selected.value.runId)
      resultMessage.value += `，Run 已恢复并收敛为 ${response.status}`
    }
    await load()
  } catch (reasonValue) {
    error.value = reasonValue instanceof Error ? reasonValue.message : '审批失败'
  } finally {
    deciding.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="module-page">
    <PageIntro
      kicker="HUMAN IN THE LOOP"
      title="把高风险工具停在执行之前"
      description="Runtime 先做 Tool Policy 判定，再创建审批。审批决定通过数据库 CAS 保证只能从 REQUESTED 迁移一次；批准后可从原预算和 Profile 恢复。"
      :endpoints="['GET /api/agent/guardrails/approvals', 'POST /api/agent/guardrails/approvals/{approvalId}/decide', 'POST /api/agent/runs/{runId}/resume']"
    >
      <div class="counter-chip"><strong>{{ pendingCount }}</strong><span>待审批</span></div>
      <button class="secondary-button" type="button" :disabled="loading" @click="load">刷新</button>
    </PageIntro>

    <p v-if="error" class="inline-error">{{ error }}</p>
    <p v-if="resultMessage" class="inline-success">{{ resultMessage }}</p>

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
            <label>审批人<input v-model="reviewer" /></label>
            <label>决策理由<textarea v-model="reason" rows="3" /></label>
            <label class="checkbox-row"><input v-model="resumeAfterApprove" type="checkbox" />批准后立即调用 Runtime resume</label>
            <div class="action-row">
              <button class="primary-button" type="button" :disabled="deciding" @click="decide(true)">批准</button>
              <button class="danger-button" type="button" :disabled="deciding" @click="decide(false)">拒绝</button>
            </div>
          </div>
          <div v-else class="decision-result">
            <span>最终决策</span>
            <strong>{{ selected.status }}</strong>
            <p>{{ selected.reviewer || '—' }} · {{ selected.decisionReason || '未填写理由' }}</p>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>
