<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { agentApi } from '../api/agent'
import JsonViewer from '../components/JsonViewer.vue'
import PageIntro from '../components/PageIntro.vue'
import StatusBadge from '../components/StatusBadge.vue'
import type { TraceRun } from '../types/agent'

type Tab = 'ops' | 'traces' | 'eval'
const activeTab = ref<Tab>('ops')
const loading = ref(false)
const actionBusy = ref(false)
const error = ref('')
const summary = ref<Record<string, unknown>>({})
const evidence = ref<Record<string, unknown>>({})
const traces = ref<TraceRun[]>([])
const traceStats = ref<Record<string, unknown>>({})
const selectedTrace = ref<TraceRun | null>(null)
const replay = ref<Array<Record<string, unknown>>>([])
const evalReports = ref<Array<Record<string, unknown>>>([])
const evalEvents = ref<Array<Record<string, unknown>>>([])
const latestEvalResult = ref<Record<string, unknown> | null>(null)

const summaryMetrics = computed(() => {
  const candidates = [
    ['Trace', summary.value.traceStats],
    ['RAG', summary.value.ragStats],
    ['Tool', summary.value.toolStats],
    ['Eval', summary.value.latestEval],
  ]
  return candidates.map(([label, value]) => ({ label: String(label), value }))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [ops, opsEvidence, traceList, stats, reports, events] = await Promise.all([
      agentApi.opsSummary(), agentApi.opsEvidence(), agentApi.traces(30), agentApi.traceStats(),
      agentApi.evalReports(20), agentApi.evalEvents(),
    ])
    summary.value = ops
    evidence.value = opsEvidence
    traces.value = traceList
    traceStats.value = stats
    evalReports.value = reports
    evalEvents.value = events
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '可观测数据加载失败'
  } finally {
    loading.value = false
  }
}

async function selectTrace(trace: TraceRun) {
  loading.value = true
  try {
    const [detail, replayEvents] = await Promise.all([agentApi.trace(trace.traceId), agentApi.traceReplay(trace.traceId)])
    selectedTrace.value = detail
    replay.value = replayEvents
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'Trace 详情加载失败'
  } finally {
    loading.value = false
  }
}

async function runEval(kind: 'regression' | 'adversarial') {
  actionBusy.value = true
  error.value = ''
  try {
    latestEvalResult.value = kind === 'regression'
      ? await agentApi.runRegressionEval()
      : await agentApi.runAdversarialEval()
    evalReports.value = await agentApi.evalReports(20)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '评测执行失败'
  } finally {
    actionBusy.value = false
  }
}

function dateTime(value: string) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(new Date(value)) : '—'
}

onMounted(load)
</script>

<template>
  <div class="module-page">
    <PageIntro
      kicker="OBSERVABILITY & EVALUATION"
      title="把一次 Agent 执行变成可解释证据"
      description="Trace 回答“发生了什么”，Replay 回答“如何重现过程”，Eval 回答“结果是否符合预期”，AgentOps 则把分散记录聚合成工程指标。"
      :endpoints="['GET /api/agent/ops/summary', 'GET /api/agent/traces', 'GET /api/agent/traces/{id}/replay', 'GET /api/agent/evals/reports']"
    >
      <button class="secondary-button" type="button" :disabled="loading" @click="load">刷新数据</button>
    </PageIntro>
    <p v-if="error" class="inline-error">{{ error }}</p>

    <section class="panel observability-panel">
      <div class="module-tabs padded-tabs">
        <button :class="{ active: activeTab === 'ops' }" @click="activeTab = 'ops'">AgentOps</button>
        <button :class="{ active: activeTab === 'traces' }" @click="activeTab = 'traces'">Trace / Replay</button>
        <button :class="{ active: activeTab === 'eval' }" @click="activeTab = 'eval'">Eval</button>
      </div>

      <div v-if="activeTab === 'ops'" class="ops-view">
        <div class="ops-metric-grid">
          <article v-for="metric in summaryMetrics" :key="metric.label">
            <span>{{ metric.label }}</span>
            <JsonViewer :value="metric.value" :collapsed="false" label="指标" />
          </article>
        </div>
        <div class="ops-detail-grid">
          <div><p class="eyebrow">SUMMARY</p><JsonViewer :value="summary" label="AgentOpsSummary" /></div>
          <div><p class="eyebrow">EVIDENCE</p><JsonViewer :value="evidence" label="AgentOpsEvidence" /></div>
        </div>
      </div>

      <div v-else-if="activeTab === 'traces'" class="trace-layout">
        <div class="trace-list">
          <JsonViewer :value="traceStats" label="Trace 聚合统计" />
          <button v-for="trace in traces" :key="trace.traceId" type="button" :class="{ selected: selectedTrace?.traceId === trace.traceId }" @click="selectTrace(trace)">
            <div><StatusBadge :value="trace.status" compact /><time>{{ dateTime(trace.startedAt) }}</time></div>
            <strong>{{ trace.question }}</strong>
            <span>{{ trace.durationMs }}ms · {{ trace.estimatedPromptTokens + trace.estimatedCompletionTokens }} tokens</span>
          </button>
          <div v-if="!traces.length" class="compact-empty">暂无 Trace</div>
        </div>
        <div class="trace-detail">
          <div v-if="!selectedTrace" class="detail-empty"><span>⌁</span><strong>选择一个 Trace</strong><p>查看 Span、Metric 与 Replay 事件。</p></div>
          <template v-else>
            <div class="detail-title"><div><p class="eyebrow">TRACE DETAIL</p><h3>{{ selectedTrace.question }}</h3></div><StatusBadge :value="selectedTrace.status" /></div>
            <div class="identity-grid">
              <div><span>Trace ID</span><code>{{ selectedTrace.traceId }}</code></div>
              <div><span>Conversation</span><code>{{ selectedTrace.conversationId }}</code></div>
              <div><span>Duration</span><strong>{{ selectedTrace.durationMs }}ms</strong></div>
              <div><span>Cost</span><strong>{{ selectedTrace.estimatedCost }}</strong></div>
            </div>
            <JsonViewer :value="selectedTrace.spans" :collapsed="false" label="Spans" />
            <JsonViewer :value="replay" :collapsed="false" label="Replay Events" />
            <JsonViewer :value="selectedTrace.metrics" label="Metrics" />
          </template>
        </div>
      </div>

      <div v-else class="eval-view">
        <div class="eval-actions">
          <div><p class="eyebrow">CONTROLLED EVALUATION</p><h3>评测会真实调用 Agent Runtime</h3><p>回归集验证功能没有退化；对抗集验证 Prompt Injection、敏感数据与工具权限。</p></div>
          <button class="secondary-button" type="button" :disabled="actionBusy" @click="runEval('regression')">运行回归评测</button>
          <button class="danger-button" type="button" :disabled="actionBusy" @click="runEval('adversarial')">运行对抗评测</button>
        </div>
        <JsonViewer v-if="latestEvalResult" :value="latestEvalResult" :collapsed="false" label="本次评测结果" />
        <div class="eval-grid">
          <article v-for="(report, index) in evalReports" :key="String(report.runId ?? index)" class="data-record">
            <div><span>{{ report.runId ?? `report-${index}` }}</span><strong>{{ report.overallScore ?? report.averageScore ?? '—' }}</strong></div>
            <JsonViewer :value="report" label="报告详情" />
          </article>
          <article class="data-record"><div><span>ONLINE EVAL EVENTS</span><strong>{{ evalEvents.length }}</strong></div><JsonViewer :value="evalEvents" label="事件快照" /></article>
        </div>
      </div>
    </section>
  </div>
</template>
