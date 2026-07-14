<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { agentApi } from '../api/agent'
import EventTimeline from '../components/EventTimeline.vue'
import JsonViewer from '../components/JsonViewer.vue'
import PageIntro from '../components/PageIntro.vue'
import StatusBadge from '../components/StatusBadge.vue'
import type { AgentEvent, AgentRunRecord, AgentStreamEvent } from '../types/agent'

const runs = ref<AgentRunRecord[]>([])
const selectedRun = ref<AgentRunRecord | null>(null)
const events = ref<AgentEvent[]>([])
const executions = ref<Array<Record<string, unknown>>>([])
const loading = ref(false)
const detailLoading = ref(false)
const error = ref('')
const stateFilter = ref('ALL')
const search = ref('')

const states = computed(() => ['ALL', ...new Set(runs.value.map((run) => run.state))])
const filteredRuns = computed(() => runs.value.filter((run) => {
  const matchesState = stateFilter.value === 'ALL' || run.state === stateFilter.value
  const keyword = search.value.trim().toLowerCase()
  const matchesSearch = !keyword || [run.runId, run.conversationId, run.userId, run.request?.question]
    .some((value) => String(value ?? '').toLowerCase().includes(keyword))
  return matchesState && matchesSearch
}))

const streamEvents = computed<AgentStreamEvent[]>(() => events.value.map((event) => ({
  eventId: event.eventId,
  traceId: event.runId,
  conversationId: event.sessionId,
  sequence: event.sequence,
  type: event.type.toLowerCase(),
  content: event.content,
  createdAt: event.createdAt,
  metadata: event.payload,
})))

function dateTime(value: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(new Date(value))
}

async function loadRuns(keepSelection = true) {
  loading.value = true
  error.value = ''
  try {
    runs.value = await agentApi.recentRuns(50)
    if (keepSelection && selectedRun.value) {
      const refreshed = runs.value.find((run) => run.runId === selectedRun.value?.runId)
      if (refreshed) selectedRun.value = refreshed
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '加载 Run 历史失败'
  } finally {
    loading.value = false
  }
}

async function selectRun(run: AgentRunRecord) {
  detailLoading.value = true
  error.value = ''
  try {
    const [detail, runEvents, toolExecutions] = await Promise.all([
      agentApi.findRun(run.runId),
      agentApi.runEvents(run.runId, -1, 1000),
      agentApi.toolExecutions(run.runId),
    ])
    selectedRun.value = detail
    events.value = runEvents
    executions.value = toolExecutions
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '加载 Run 详情失败'
  } finally {
    detailLoading.value = false
  }
}

async function cancelSelected() {
  if (!selectedRun.value) return
  await agentApi.cancelRun(selectedRun.value.runId)
  await selectRun(selectedRun.value)
  await loadRuns()
}

async function resumeSelected() {
  if (!selectedRun.value) return
  detailLoading.value = true
  try {
    await agentApi.resumeRun(selectedRun.value.runId)
    await selectRun(selectedRun.value)
    await loadRuns()
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => loadRuns(false))
</script>

<template>
  <div class="module-page">
    <PageIntro
      kicker="PERSISTED RUNS"
      title="从状态记录回放一条 Agent 执行"
      description="SSE 是实时投影，PostgreSQL 中的 RunRecord 与 AgentEvent 才是事实源。选择任意 Run，对照阶段、预算、工具结果和事件序号。"
      :endpoints="['GET /api/agent/runs', 'GET /api/agent/runs/{runId}', 'GET /api/agent/runs/{runId}/events']"
    >
      <button class="secondary-button" type="button" :disabled="loading" @click="loadRuns()">刷新列表</button>
    </PageIntro>

    <p v-if="error" class="inline-error">{{ error }}</p>

    <div class="history-layout">
      <section class="panel list-panel">
        <div class="list-toolbar">
          <input v-model="search" placeholder="搜索 Run ID、Session 或问题" />
          <select v-model="stateFilter">
            <option v-for="state in states" :key="state" :value="state">{{ state === 'ALL' ? '全部状态' : state }}</option>
          </select>
        </div>

        <div class="run-list">
          <button
            v-for="run in filteredRuns"
            :key="run.runId"
            type="button"
            class="run-card"
            :class="{ selected: selectedRun?.runId === run.runId }"
            @click="selectRun(run)"
          >
            <div class="run-card-top">
              <StatusBadge :value="run.state" compact />
              <time>{{ dateTime(run.updatedAt) }}</time>
            </div>
            <strong>{{ run.request?.question || '无问题文本' }}</strong>
            <span>{{ run.conversationId }}</span>
            <code>{{ run.runId }}</code>
          </button>
          <div v-if="!filteredRuns.length" class="compact-empty">{{ loading ? '正在读取 Run…' : '没有符合条件的 Run' }}</div>
        </div>
      </section>

      <section class="panel detail-panel">
        <div v-if="!selectedRun" class="detail-empty">
          <span>↳</span>
          <strong>选择左侧一条 Run</strong>
          <p>这里会展示持久化检查点，而不是重新执行 Agent。</p>
        </div>

        <template v-else>
          <div class="detail-title">
            <div>
              <p class="eyebrow">RUN CHECKPOINT</p>
              <h3>{{ selectedRun.request?.question }}</h3>
            </div>
            <StatusBadge :value="selectedRun.state" />
          </div>
          <div class="identity-grid">
            <div><span>Run ID</span><code>{{ selectedRun.runId }}</code></div>
            <div><span>Conversation</span><code>{{ selectedRun.conversationId }}</code></div>
            <div><span>Phase</span><strong>{{ selectedRun.phase }}</strong></div>
            <div><span>Resume</span><strong>{{ selectedRun.resumeCount }}</strong></div>
          </div>

          <div class="detail-actions">
            <button v-if="selectedRun.state === 'RUNNING'" class="danger-button" type="button" @click="cancelSelected">请求取消</button>
            <button v-if="selectedRun.state === 'WAITING_APPROVAL'" class="primary-button" type="button" @click="resumeSelected">检查审批并恢复</button>
          </div>

          <div v-if="selectedRun.answer" class="record-answer"><span>FINAL ANSWER</span>{{ selectedRun.answer }}</div>
          <div v-if="selectedRun.failureReason" class="record-failure"><span>FAILURE</span>{{ selectedRun.failureReason }}</div>

          <div class="budget-grid" v-if="selectedRun.budgetSnapshot">
            <div><span>Turns</span><strong>{{ selectedRun.budgetSnapshot.turns }}</strong></div>
            <div><span>Models</span><strong>{{ selectedRun.budgetSnapshot.modelCalls }}</strong></div>
            <div><span>Tools</span><strong>{{ selectedRun.budgetSnapshot.toolCalls }}</strong></div>
            <div><span>Input</span><strong>{{ selectedRun.budgetSnapshot.inputTokens }}</strong></div>
            <div><span>Output</span><strong>{{ selectedRun.budgetSnapshot.outputTokens }}</strong></div>
          </div>

          <div class="detail-tabs-summary">
            <span>{{ events.length }} events</span>
            <span>{{ executions.length }} tool executions</span>
            <span>{{ selectedRun.usedTools.length }} used tools</span>
          </div>

          <EventTimeline :events="streamEvents" :active="detailLoading" />
          <JsonViewer :value="selectedRun" label="完整 RunRecord" />
          <JsonViewer v-if="executions.length" :value="executions" label="工具幂等执行记录" />
        </template>
      </section>
    </div>
  </div>
</template>
