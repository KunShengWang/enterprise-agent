<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentApi } from '../api/agent'
import EventTimeline from '../components/EventTimeline.vue'
import JsonViewer from '../components/JsonViewer.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAgentStream } from '../composables/useAgentStream'
import type { AgentRequest, OrderCareCaseSnapshot } from '../types/agent'

const stream = useAgentStream()
const route = useRoute()
const router = useRouter()
const conversationId = ref(`learn-${new Date().toISOString().slice(0, 10)}`)
const userId = ref('student-001')
const scenarioId = ref('ordercare-floworder-v1')
const question = ref('请诊断 requestId=ORDERCARE-M05-REQUEST，列出关键事实、风险和下一步建议。')
const metadataText = ref('{\n  "source": "ordercare-workbench",\n  "mode": "read-only-diagnosis"\n}')
const showAdvanced = ref(false)
const formError = ref('')
const decisionBusy = ref(false)
const reviewer = ref('student-reviewer')
const decisionReason = ref('已确认本次工具调用参数与影响范围')
const loadingRun = ref(false)
const startedAt = ref(0)
const now = ref(Date.now())
const submittedQuestion = ref('')
let clock: number | undefined

const examples = [
  { label: 'requestId 诊断', value: '请诊断 requestId=ORDERCARE-M05-REQUEST，列出关键事实、风险和下一步建议。' },
  { label: 'orderNo 定位', value: '订单号 ORDERCARE-M05-ORDER 的库存为什么没有释放？请基于证据诊断。' },
  { label: 'deductNo 定位', value: '检查扣减流水 ORDERCARE-M05-DEDUCT 是否存在可恢复的死信。' },
  { label: '解释恢复 SOP', value: '诊断 requestId=ORDERCARE-M05-REQUEST，并结合 OrderCare SOP 解释为什么当前允许或禁止恢复。' },
]

const orderCareCase = computed<OrderCareCaseSnapshot | null>(() => {
  const result = stream.runRecord.value?.toolResults.find((item) => item.toolName === 'floworder_case_inspect')
  if (!result?.success || !result.content) return null
  try {
    return JSON.parse(result.content) as OrderCareCaseSnapshot
  } catch {
    return null
  }
})

const currentState = computed(() => {
  if (stream.running.value) return 'RUNNING'
  if (stream.runRecord.value?.state) return stream.runRecord.value.state
  if (stream.approvalId.value) return 'WAITING_APPROVAL'
  const type = stream.lastEvent.value?.type
  if (type === 'run_completed') return 'COMPLETED'
  if (type === 'run_failed' || type === 'transport_error') return 'FAILED'
  if (type === 'run_cancelled') return 'CANCELLED'
  return 'READY'
})

const elapsed = computed(() => {
  if (!startedAt.value) return '0.0s'
  const end = stream.running.value ? now.value : Date.now()
  return `${Math.max(0, end - startedAt.value) / 1000}`.replace(/(\.\d).*/, '$1s')
})

const sequence = computed(() => Math.max(0, ...stream.events.value.map((event) => event.sequence)))
const budget = computed(() => stream.runRecord.value?.budgetSnapshot)
const hasConversation = computed(() => Boolean(
  submittedQuestion.value
  || stream.runId.value
  || stream.events.value.length
  || stream.answer.value,
))
const stages = computed(() => {
  const types = new Set(stream.events.value.map((event) => event.type))
  const ended = ['run_completed', 'run_failed', 'run_cancelled'].some((type) => types.has(type))
  const definitions = [
    { key: 'start', label: '接收任务', hit: types.has('run_started') },
    { key: 'context', label: '组装上下文', hit: types.has('context_prepared') || types.has('context_compacted') },
    { key: 'model', label: '模型决策', hit: types.has('model_started') || types.has('model_completed') },
    { key: 'tool', label: '工具 / 审批', hit: [...types].some((type) => type.includes('tool') || type.includes('approval') || type === 'policy_decided') },
    { key: 'finish', label: '结果收敛', hit: ended },
  ]
  const lastHit = definitions.reduce((last, stage, index) => stage.hit ? index : last, -1)
  return definitions.map((stage, index) => ({
    ...stage,
    status: stage.hit ? 'done' : stream.running.value && index === lastHit + 1 ? 'active' : 'pending',
  }))
})

async function submit() {
  formError.value = ''
  if (!question.value.trim()) {
    formError.value = '请输入要交给 Agent 的任务。'
    return
  }
  let metadata: Record<string, unknown>
  try {
    metadata = JSON.parse(metadataText.value) as Record<string, unknown>
    if (!metadata || Array.isArray(metadata) || typeof metadata !== 'object') {
      throw new Error('metadata must be an object')
    }
  } catch {
    formError.value = 'Metadata 必须是合法的 JSON 对象。'
    showAdvanced.value = true
    return
  }

  startedAt.value = Date.now()
  const request: AgentRequest = {
    conversationId: conversationId.value.trim(),
    userId: userId.value.trim(),
    question: question.value.trim(),
    metadata,
    scenarioId: scenarioId.value,
  }
  submittedQuestion.value = request.question
  await stream.start(request)
}

function resetWorkbench() {
  stream.reset()
  submittedQuestion.value = ''
  startedAt.value = 0
  formError.value = ''
  void router.replace({ name: 'runtime' })
}

async function decide(approved: boolean) {
  if (!stream.approvalId.value) return
  decisionBusy.value = true
  formError.value = ''
  try {
    await agentApi.decideApproval(
      stream.approvalId.value,
      approved,
      reviewer.value.trim() || 'learning-console',
      decisionReason.value.trim(),
    )
    await stream.resume()
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '审批或恢复失败'
  } finally {
    decisionBusy.value = false
  }
}

function routeRunId(value: unknown) {
  if (Array.isArray(value)) {
    return String(value[0] ?? '').trim()
  }
  return typeof value === 'string' ? value.trim() : ''
}

async function openPersistedRun(targetRunId: string) {
  loadingRun.value = true
  formError.value = ''
  try {
    const [run, events] = await Promise.all([
      agentApi.findRun(targetRunId),
      agentApi.runEvents(targetRunId, -1, 1000),
    ])
    await stream.hydrate(run, events)
    submittedQuestion.value = run.request?.question ?? ''
    conversationId.value = run.conversationId
    userId.value = run.userId
    question.value = run.request?.question ?? ''
    metadataText.value = JSON.stringify(run.request?.metadata ?? {}, null, 2)
    scenarioId.value = run.request?.scenarioId || 'ordercare-floworder-v1'
    startedAt.value = 0
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '加载持久化 Run 失败'
  } finally {
    loadingRun.value = false
  }
}

async function copyRunId() {
  if (stream.runId.value) {
    await window.navigator.clipboard.writeText(stream.runId.value)
  }
}

watch(
  () => route.query.runId,
  (value) => {
    const targetRunId = routeRunId(value)
    if (!targetRunId || targetRunId === stream.runId.value || stream.running.value) return
    void openPersistedRun(targetRunId)
  },
  { immediate: true },
)

watch(
  () => stream.runId.value,
  (value) => {
    if (!value || routeRunId(route.query.runId) === value) return
    void router.replace({ name: 'runtime', query: { runId: value } })
  },
)

onMounted(() => {
  clock = window.setInterval(() => { now.value = Date.now() }, 100)
})

onBeforeUnmount(() => {
  if (clock) window.clearInterval(clock)
  if (stream.running.value) stream.cancel()
})
</script>

<template>
  <div class="codex-workbench">
    <section class="conversation-panel panel">
      <header class="conversation-toolbar">
        <div class="conversation-title">
          <span class="assistant-avatar">✦</span>
          <div>
            <strong>OrderCare Incident Agent</strong>
            <small>FlowOrder 异常订单 · 统一 Runtime 只读诊断</small>
          </div>
        </div>
        <StatusBadge :value="currentState" />
      </header>

      <div class="conversation-scroll">
        <div v-if="!hasConversation" class="conversation-welcome">
          <span class="welcome-mark">✦</span>
          <h2>今天想让 Agent 做什么？</h2>
          <p>发送任务后，回答会留在正文中；上下文、模型决策、工具和审批过程会同步出现在右侧。</p>
        </div>

        <article v-if="submittedQuestion" class="chat-turn user-turn">
          <div class="turn-avatar">你</div>
          <div class="turn-content">
            <span class="turn-role">YOU</span>
            <p>{{ submittedQuestion }}</p>
          </div>
        </article>

        <article v-if="hasConversation" class="chat-turn assistant-turn">
          <div class="turn-avatar">✦</div>
          <div class="turn-content">
            <div class="assistant-heading">
              <span class="turn-role">AGENT</span>
              <span v-if="stream.running.value" class="live-indicator"><i /> WORKING</span>
            </div>
            <div v-if="stream.answer.value" class="answer-content">{{ stream.answer.value }}</div>
            <div v-else-if="stream.running.value" class="assistant-thinking">
              <span /><span /><span />
              <p>正在读取上下文并执行任务…</p>
            </div>
            <div v-else class="answer-empty">
              <p>Runtime 尚未返回最终回答。你可以在右侧查看它停在了哪个阶段。</p>
            </div>

            <section v-if="orderCareCase" class="ordercare-case-card">
              <div class="case-card-heading">
                <div>
                  <span>FLOWORDER CASE</span>
                  <strong>{{ orderCareCase.diagnosisCode }}</strong>
                </div>
                <StatusBadge :value="orderCareCase.recoveryEligible ? 'CANDIDATE' : 'READ_ONLY'" />
              </div>
              <div class="case-fact-grid">
                <div><span>Request</span><code>{{ orderCareCase.canonicalRequestId || '未定位' }}</code></div>
                <div><span>Order</span><strong>{{ orderCareCase.order?.statusName || orderCareCase.order?.queryError || 'UNKNOWN' }}</strong></div>
                <div><span>Deduct</span><strong>{{ orderCareCase.deduct?.statusName || 'NOT_FOUND' }}</strong></div>
                <div><span>Inventory</span><strong>{{ orderCareCase.inventory?.invariantOk ? 'INVARIANT_OK' : 'CHECK_REQUIRED' }}</strong></div>
              </div>
              <div class="case-evidence">
                <span v-for="item in orderCareCase.evidence" :key="item">{{ item }}</span>
              </div>
              <div v-if="orderCareCase.hardRisks.length" class="case-risks">
                <strong>硬风险</strong>
                <span v-for="risk in orderCareCase.hardRisks" :key="risk">{{ risk }}</span>
              </div>
              <div v-if="orderCareCase.candidates.length" class="case-candidate">
                <span>候选动作由 FlowOrder 生成</span>
                <code>{{ orderCareCase.candidates[0].candidateId }}</code>
                <strong>{{ orderCareCase.candidates[0].eligible ? '可进入预演' : `阻断：${orderCareCase.candidates[0].blockedBy}` }}</strong>
              </div>
            </section>

            <div v-if="budget" class="budget-grid">
              <div><span>Turns</span><strong>{{ budget.turns }}</strong></div>
              <div><span>Model calls</span><strong>{{ budget.modelCalls }}</strong></div>
              <div><span>Tool calls</span><strong>{{ budget.toolCalls }}</strong></div>
              <div><span>Tokens</span><strong>{{ budget.inputTokens + budget.outputTokens }}</strong></div>
              <div><span>Cost</span><strong>{{ budget.estimatedCost.toFixed(6) }}</strong></div>
            </div>

            <JsonViewer v-if="stream.runRecord.value" :value="stream.runRecord.value" label="查看完整 RunRecord" />
          </div>
        </article>
      </div>

      <div class="composer-area">
        <div class="example-row">
          <button v-for="example in examples" :key="example.label" type="button" :disabled="stream.running.value || loadingRun" @click="question = example.value">
            {{ example.label }}
          </button>
        </div>

        <div v-if="showAdvanced" class="advanced-grid">
          <label>
            <span>conversationId</span>
            <input v-model="conversationId" :disabled="stream.running.value" />
          </label>
          <label>
            <span>userId</span>
            <input v-model="userId" :disabled="stream.running.value" />
          </label>
          <label>
            <span>scenarioId（服务端白名单）</span>
            <select v-model="scenarioId" :disabled="stream.running.value">
              <option value="ordercare-floworder-v1">ordercare-floworder-v1</option>
              <option value="">默认学习场景</option>
            </select>
          </label>
          <label class="metadata-field">
            <span>metadata JSON</span>
            <textarea v-model="metadataText" rows="4" :disabled="stream.running.value" spellcheck="false" />
          </label>
        </div>

        <p v-if="formError" class="inline-error">{{ formError }}</p>
        <div class="composer-box">
          <textarea
            id="question"
            v-model="question"
            rows="3"
            :disabled="stream.running.value || loadingRun"
            aria-label="发送给 Agent 的任务"
            placeholder="给 Agent 一个任务…"
            @keydown.ctrl.enter.prevent="submit"
          />
          <div class="composer-toolbar">
            <button class="composer-option" type="button" @click="showAdvanced = !showAdvanced">
              <span>{{ showAdvanced ? '−' : '+' }}</span> 上下文
            </button>
            <code>POST /api/agent/runs/events</code>
            <button v-if="stream.running.value" class="stop-button" type="button" aria-label="停止当前 Run" @click="stream.cancel">■</button>
            <button v-else class="send-button" type="button" :disabled="loadingRun" aria-label="发送任务" @click="submit">↑</button>
          </div>
        </div>
        <div class="composer-footer">
          <span>Ctrl + Enter 发送 · SSE 实时接收</span>
          <button type="button" :disabled="stream.running.value || loadingRun" @click="resetWorkbench">新建会话</button>
        </div>
      </div>
    </section>

    <aside class="execution-panel panel">
      <div class="execution-header">
        <div>
          <p class="eyebrow">RUNTIME ACTIVITY</p>
          <h2>执行详情</h2>
        </div>
        <div class="runtime-metrics">
          <span><small>SEQ</small>{{ sequence }}</span>
          <span><small>EVENTS</small>{{ stream.persistedEvents.value.length }}</span>
          <span><small>TIME</small>{{ elapsed }}</span>
        </div>
      </div>

      <div class="stage-rail">
        <div v-for="(stage, index) in stages" :key="stage.key" :class="`is-${stage.status}`">
          <span>{{ stage.status === 'done' ? '✓' : index + 1 }}</span>
          <small>{{ stage.label }}</small>
          <i v-if="index < stages.length - 1" />
        </div>
      </div>

      <div v-if="stream.runId.value" class="run-identity">
        <span>RUN ID</span>
        <code>{{ stream.runId.value }}</code>
        <button type="button" title="复制 Run ID" @click="copyRunId">复制</button>
      </div>

      <div v-if="stream.error.value" class="stream-warning">
        <strong>执行提示</strong>
        <p>{{ stream.error.value }}</p>
      </div>

      <div v-if="stream.approvalId.value && currentState === 'WAITING_APPROVAL'" class="approval-callout">
        <div>
          <p class="eyebrow">HUMAN IN THE LOOP</p>
          <h3>高风险工具等待审批</h3>
          <code>{{ stream.approvalId.value }}</code>
        </div>
        <label>审批人<input v-model="reviewer" /></label>
        <label>决策理由<input v-model="decisionReason" /></label>
        <div class="approval-actions">
          <button class="primary-button" type="button" :disabled="decisionBusy" @click="decide(true)">批准并继续执行</button>
          <button class="danger-button" type="button" :disabled="decisionBusy" @click="decide(false)">拒绝并结束</button>
        </div>
      </div>

      <EventTimeline :events="stream.events.value" :active="stream.running.value" />
    </aside>
  </div>
</template>
