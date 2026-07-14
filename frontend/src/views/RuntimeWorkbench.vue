<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { agentApi } from '../api/agent'
import EventTimeline from '../components/EventTimeline.vue'
import JsonViewer from '../components/JsonViewer.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { useAgentStream } from '../composables/useAgentStream'
import type { AgentRequest } from '../types/agent'

const stream = useAgentStream()
const conversationId = ref(`learn-${new Date().toISOString().slice(0, 10)}`)
const userId = ref('student-001')
const question = ref('发布失败时应该先检查什么？请结合知识库给出排查顺序。')
const metadataText = ref('{\n  "source": "learning-console",\n  "mode": "runtime-observation"\n}')
const showAdvanced = ref(false)
const formError = ref('')
const decisionBusy = ref(false)
const reviewer = ref('student-reviewer')
const decisionReason = ref('已确认本次工具调用参数与影响范围')
const startedAt = ref(0)
const now = ref(Date.now())
let clock: number | undefined

const examples = [
  { label: '知识库问答', value: '发布失败时应该先检查什么？请结合知识库给出排查顺序。' },
  { label: '普通对话', value: '请用三句话解释统一 Agent Loop 为什么比固定路由更重要。' },
  { label: '工具调用', value: '请查询工单 TICKET-1001 的当前状态。' },
  { label: '触发审批', value: '请把工单 TICKET-1001 的优先级修改为 P1。' },
]

const currentState = computed(() => {
  if (stream.runRecord.value?.state) return stream.runRecord.value.state
  if (stream.running.value) return 'RUNNING'
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
  }
  await stream.start(request)
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
    await agentApi.resumeRun(stream.runId.value)
    await stream.refresh()
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '审批或恢复失败'
  } finally {
    decisionBusy.value = false
  }
}

async function copyRunId() {
  if (stream.runId.value) {
    await window.navigator.clipboard.writeText(stream.runId.value)
  }
}

onMounted(() => {
  clock = window.setInterval(() => { now.value = Date.now() }, 100)
})

onBeforeUnmount(() => {
  if (clock) window.clearInterval(clock)
  if (stream.running.value) stream.cancel()
})
</script>

<template>
  <div class="workbench-layout">
    <section class="task-panel panel">
      <div class="section-heading">
        <div>
          <p class="eyebrow">TASK INPUT</p>
          <h2>给 Runtime 一个任务</h2>
        </div>
        <StatusBadge :value="currentState" />
      </div>

      <div class="example-row">
        <button v-for="example in examples" :key="example.label" type="button" @click="question = example.value">
          {{ example.label }}
        </button>
      </div>

      <label class="field-label" for="question">用户问题</label>
      <textarea id="question" v-model="question" rows="7" :disabled="stream.running.value" placeholder="输入一个可以触发 RAG、Tool 或审批的任务…" />

      <button class="advanced-toggle" type="button" @click="showAdvanced = !showAdvanced">
        <span>{{ showAdvanced ? '−' : '+' }}</span> 请求上下文
      </button>
      <div v-if="showAdvanced" class="advanced-grid">
        <label>
          <span>conversationId</span>
          <input v-model="conversationId" :disabled="stream.running.value" />
        </label>
        <label>
          <span>userId</span>
          <input v-model="userId" :disabled="stream.running.value" />
        </label>
        <label class="metadata-field">
          <span>metadata JSON</span>
          <textarea v-model="metadataText" rows="5" :disabled="stream.running.value" spellcheck="false" />
        </label>
      </div>

      <p v-if="formError" class="inline-error">{{ formError }}</p>
      <div class="action-row">
        <button v-if="!stream.running.value" class="primary-button" type="button" @click="submit">
          <span>▶</span> 启动 Agent Run
        </button>
        <button v-else class="danger-button" type="button" @click="stream.cancel">
          <span>■</span> 取消当前 Run
        </button>
        <button class="secondary-button" type="button" :disabled="stream.running.value" @click="stream.reset">
          清空观察台
        </button>
      </div>

      <div class="learning-note">
        <strong>这里发送了什么？</strong>
        <p><code>POST /api/agent/runs/events</code> 返回 SSE。每个事件先由 Runtime 落库，再转发到当前页面。</p>
      </div>
    </section>

    <section class="runtime-panel panel">
      <div class="runtime-toolbar">
        <div>
          <p class="eyebrow">LIVE RUNTIME</p>
          <h2>执行过程</h2>
        </div>
        <div class="runtime-metrics">
          <span><small>SEQ</small>{{ sequence }}</span>
          <span><small>EVENTS</small>{{ stream.persistedEvents.value.length }}</span>
          <span><small>ELAPSED</small>{{ elapsed }}</span>
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
          <button class="primary-button" type="button" :disabled="decisionBusy" @click="decide(true)">批准并恢复</button>
          <button class="danger-button" type="button" :disabled="decisionBusy" @click="decide(false)">拒绝并结束</button>
        </div>
      </div>

      <EventTimeline :events="stream.events.value" :active="stream.running.value" />
    </section>

    <section class="answer-panel panel">
      <div class="section-heading">
        <div>
          <p class="eyebrow">ASSISTANT OUTPUT</p>
          <h2>最终回答</h2>
        </div>
        <span v-if="stream.running.value" class="live-indicator"><i /> LIVE</span>
      </div>
      <div v-if="stream.answer.value" class="answer-content">{{ stream.answer.value }}</div>
      <div v-else class="answer-empty">
        <span>⌁</span>
        <p>模型产生最终文本后会显示在这里。若 Runtime 发布 <code>model_delta</code>，页面会逐段追加。</p>
      </div>

      <div v-if="budget" class="budget-grid">
        <div><span>Turns</span><strong>{{ budget.turns }}</strong></div>
        <div><span>Model calls</span><strong>{{ budget.modelCalls }}</strong></div>
        <div><span>Tool calls</span><strong>{{ budget.toolCalls }}</strong></div>
        <div><span>Tokens</span><strong>{{ budget.inputTokens + budget.outputTokens }}</strong></div>
        <div><span>Cost</span><strong>{{ budget.estimatedCost.toFixed(6) }}</strong></div>
      </div>

      <JsonViewer v-if="stream.runRecord.value" :value="stream.runRecord.value" label="查看完整 RunRecord" />
    </section>
  </div>
</template>
