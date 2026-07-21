<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import type { ConversationItem, PresentationLocator } from '../types/conversation'

const props = defineProps<{
  item: ConversationItem
  busy?: boolean
  reviewer: string
  decisionReason: string
}>()

const emit = defineEmits<{
  confirmPreview: [approved: boolean]
  decideApproval: [approved: boolean]
  copy: [content: string]
  retry: []
  diagnostics: []
  supplyInput: []
  locatePresentations: [locator: PresentationLocator]
  'update:reviewer': [value: string]
  'update:decisionReason': [value: string]
}>()

const markdown = computed(() => renderMarkdown(props.item.content))
const itemIcon = computed(() => ({
  USER_MESSAGE: '你', AGENT_STATUS: 'A', TASK_PLAN: '≡', ROUTE_SUMMARY: 'A',
  TOOL_CALL: '⌘', TOOL_RESULT: '⌘', AGENT_DELEGATION: '↳', INCIDENT_PREVIEW: '!',
  APPROVAL_REQUEST: '✓', FINAL_ANSWER: 'A', ERROR: '!', EXECUTION_NARRATIVE: 'A',
}[props.item.type]))

function shortValue(value: unknown) {
  if (Array.isArray(value)) return value.join('、')
  if (value && typeof value === 'object') return Object.entries(value as Record<string, unknown>)
    .map(([key, nested]) => `${key}: ${String(nested)}`).join('；')
  return String(value ?? '')
}

function statusLabel(status: string) {
  return ({ pending: '未开始', active: '执行中', completed: '已完成', failed: '失败', waiting: '等待操作' } as Record<string, string>)[status] ?? status
}

function attachmentSize(size: number) {
  return size < 1024 ? `${size} B` : `${Math.ceil(size / 1024)} KB`
}

function previewInput(key: string) {
  const value = props.item.preview?.payload.validatedInput
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''
  return shortValue((value as Record<string, unknown>)[key])
}

const previewValidatedInput = computed<Record<string, unknown>>(() => {
  const value = props.item.preview?.payload.validatedInput
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {}
})

const isScopeDiscoveryPreview = computed(() => Boolean(previewValidatedInput.value.scopeSnapshotId))
const scopeCandidates = computed<Record<string, unknown>[]>(() => {
  const value = previewValidatedInput.value.scopeCandidates
  return Array.isArray(value)
    ? value.filter(candidate => candidate && typeof candidate === 'object' && !Array.isArray(candidate)) as Record<string, unknown>[]
    : []
})
const scopeQueues = computed(() => listValue(previewValidatedInput.value.queueNames))
const specialistSummary = computed(() => scopeQueues.value.length
  ? 'Order、Inventory、MQ Specialist + Reviewer'
  : 'Order、Inventory Specialist + Reviewer')

function listValue(value: unknown) {
  if (Array.isArray(value)) return value.map(item => String(item)).filter(Boolean)
  return value === undefined || value === null || value === '' ? [] : [String(value)]
}

function previewTimeRange() {
  const start = String(previewValidatedInput.value.timeStart ?? '')
  const end = String(previewValidatedInput.value.timeEnd ?? '')
  if (!start || !end) return ''
  return `${new Date(start).toLocaleString('zh-CN')} 至 ${new Date(end).toLocaleString('zh-CN')}`
}

function sourceHealthSummary() {
  const health = previewValidatedInput.value.sourceHealth
  if (!health || typeof health !== 'object' || Array.isArray(health)) return ''
  return Object.entries(health as Record<string, unknown>)
    .map(([source, status]) => `${source}: ${String(status)}`).join('；')
}

function candidateText(candidate: Record<string, unknown>, key: string) {
  return shortValue(candidate[key]) || '—'
}

const answerStatus = computed(() => ({
  WAITING: '等待模型输出', STREAMING: '正在生成', FINALIZING: '正在确认最终结果',
  COMPLETED: '已完成', FAILED: '执行失败', CANCELLED: '已取消', IDLE: '',
} as Record<string, string>)[props.item.answerState ?? 'IDLE'])
const collapsibleSummary = computed(() => ['TASK_UNDERSTANDING', 'ROUTE_SUMMARY']
  .includes(props.item.presentationKind ?? ''))
const markdownRoot = ref<HTMLElement | null>(null)
const expandedNarrativeId = ref('')

function defaultExpandedNarrative() {
  return props.item.narrative?.items.find(entry => entry.status === 'active')?.id ?? ''
}

function toggleNarrative(id: string) {
  expandedNarrativeId.value = expandedNarrativeId.value === id ? '' : id
}

async function decorateCodeBlocks() {
  await nextTick()
  markdownRoot.value?.querySelectorAll('pre').forEach(pre => {
    if (pre.querySelector(':scope > .copy-code-button')) return
    const button = document.createElement('button')
    button.type = 'button'
    button.className = 'copy-code-button'
    button.textContent = '复制代码'
    button.setAttribute('aria-label', '复制代码块')
    button.addEventListener('click', async () => {
      await navigator.clipboard.writeText(pre.querySelector('code')?.textContent ?? pre.textContent ?? '')
      button.textContent = '已复制'
      window.setTimeout(() => { button.textContent = '复制代码' }, 1200)
    })
    pre.prepend(button)
  })
}
watch(markdown, decorateCodeBlocks, { immediate: true })
watch(() => props.item.id, () => { expandedNarrativeId.value = defaultExpandedNarrative() }, { immediate: true })
watch(() => props.item.narrative?.items.map(entry => entry.status).join(','), () => {
  if (!expandedNarrativeId.value) expandedNarrativeId.value = defaultExpandedNarrative()
})
</script>

<template>
  <article class="conversation-item" :data-type="item.type.toLowerCase()" :data-status="item.status" :data-presentation="item.presentationKind?.toLowerCase()">
    <div class="conversation-item-icon">{{ itemIcon }}</div>
    <div class="conversation-item-body">
      <header v-if="item.type !== 'USER_MESSAGE' && !collapsibleSummary">
        <div><strong>{{ item.title }}</strong><span v-if="item.live" class="live-label">{{ answerStatus || '正在生成' }}</span></div>
        <small v-if="!['FINAL_ANSWER', 'ROUTE_SUMMARY'].includes(item.type)" class="item-state"><i />{{ statusLabel(item.status) }}</small>
      </header>

      <template v-if="item.type === 'USER_MESSAGE'">
        <p class="user-message-text">{{ item.content }}</p>
        <div v-if="item.attachments?.length" class="message-attachments" aria-label="本次消息的附件">
          <span v-for="attachment in item.attachments" :key="`${attachment.name}-${attachment.size}`">
            <i aria-hidden="true">▤</i><span><strong>{{ attachment.name }}</strong><small>{{ attachmentSize(attachment.size) }}</small></span>
          </span>
        </div>
      </template>

      <details v-else-if="collapsibleSummary" class="public-summary" open>
        <summary><strong>任务理解</strong><span>{{ statusLabel(item.status) }}</span></summary>
        <p>{{ item.content }}</p>
      </details>

      <template v-else-if="item.type === 'TASK_PLAN'">
        <p class="item-description">{{ item.content }}</p>
        <ol class="task-plan-list"><li v-for="(step, index) in item.steps" :key="step"><span>{{ index + 1 }}</span>{{ step }}</li></ol>
      </template>

      <template v-else-if="item.type === 'TOOL_CALL' || item.type === 'TOOL_RESULT'">
        <div class="tool-summary-row">
          <div><span>工具</span><strong>{{ item.tool?.displayName }}</strong></div>
          <div><span>状态</span><strong>{{ statusLabel(item.status) }}</strong></div>
          <div v-if="item.tool?.durationMs !== undefined"><span>耗时</span><strong>{{ item.tool.durationMs }} ms</strong></div>
          <div v-if="item.tool?.resultCount !== undefined"><span>结果</span><strong>{{ item.tool.resultCount }} 条</strong></div>
          <div v-if="item.tool?.attemptLabel"><span>尝试</span><strong>{{ item.tool.attemptLabel }}</strong></div>
        </div>
        <p v-if="item.tool?.actionSummary" class="tool-action-summary">{{ item.tool.actionSummary }}</p>
        <p class="tool-result-summary">{{ item.tool?.summary }}</p>
        <details v-if="item.tool">
          <summary>查看输入与结果</summary>
          <div class="tool-detail-grid single">
            <section><strong>输入参数</strong><dl><template v-for="(value, key) in item.tool.arguments" :key="key"><dt>{{ key }}</dt><dd>{{ shortValue(value) }}</dd></template></dl></section>
          </div>
        </details>
      </template>

      <template v-else-if="item.type === 'AGENT_DELEGATION'">
        <p class="item-description">{{ item.content }}</p>
      </template>

      <template v-else-if="item.type === 'EXECUTION_NARRATIVE' && item.narrative">
        <ol class="execution-narrative-list">
          <li v-for="entry in item.narrative.items" :key="entry.id" :data-status="entry.status">
            <i aria-hidden="true" />
            <div class="execution-narrative-entry">
              <div class="execution-narrative-heading">
                <button class="execution-narrative-toggle" type="button" :aria-expanded="expandedNarrativeId === entry.id" @click="toggleNarrative(entry.id)">
                  <strong>{{ entry.summary }}</strong><span v-if="entry.detail">{{ entry.detail }}</span>
                  <small>{{ expandedNarrativeId === entry.id ? '收起详情' : '展开详情' }}</small>
                </button>
                <button class="execution-inspector-link" type="button" @click.stop="emit('locatePresentations', { turnId: item.narrative!.turnId, presentationIds: entry.sourcePresentationIds })">在检查器中打开</button>
              </div>
              <section v-if="expandedNarrativeId === entry.id" class="execution-narrative-detail">
                <dl>
                  <template v-for="field in entry.metadata" :key="`${entry.id}-${field.label}`">
                    <dt>{{ field.label }}</dt><dd :class="{ code: field.code }">{{ field.value }}</dd>
                  </template>
                </dl>
                <div v-if="entry.findings.length" class="execution-findings">
                  <strong>关键发现</strong><ul><li v-for="finding in entry.findings" :key="finding">{{ finding }}</li></ul>
                </div>
                <div v-if="entry.detail" class="execution-output-summary"><strong>公开输出摘要</strong><p>{{ entry.detail }}</p></div>
              </section>
            </div>
          </li>
        </ol>
      </template>

      <template v-else-if="item.presentationKind === 'WAITING_FOR_USER'">
        <p class="item-description">{{ item.content }}</p>
        <ul class="clarification-list"><li v-for="step in item.steps" :key="step">{{ step }}</li></ul>
        <div class="inline-actions"><button class="primary-button" type="button" @click="emit('supplyInput')">补充信息</button></div>
      </template>

      <template v-else-if="item.type === 'INCIDENT_PREVIEW'">
        <p class="item-description">{{ item.content }}</p>
        <div class="preview-facts">
          <span>执行方式 <strong>{{ item.preview?.targetId === 'INCIDENT_INVESTIGATION' ? '只读 Multi-Agent 调查' : item.preview?.targetId }}</strong></span>
          <span v-if="previewTimeRange()">时间范围 <strong>{{ previewTimeRange() }}</strong></span>
          <span v-if="previewInput('timezone')">时区 <strong>{{ previewInput('timezone') }}{{ previewInput('defaultTimezoneUsed') === 'true' ? '（使用系统默认）' : '' }}</strong></span>
          <span v-if="previewInput('anomalyTypes')">异常类型 <strong>{{ previewInput('anomalyTypes') }}</strong></span>
          <span v-if="previewInput('candidateCount')">候选数量 <strong>{{ previewInput('candidateCount') }}</strong></span>
          <span v-if="previewInput('requestIds')">调查范围 <strong>{{ previewInput('requestIds') }}</strong></span>
          <span v-if="previewInput('queueNames') || previewInput('queueName')">观察队列 <strong>{{ previewInput('queueNames') || previewInput('queueName') }}</strong></span>
          <span v-if="item.preview?.targetId === 'INCIDENT_INVESTIGATION'">参与角色 <strong>{{ specialistSummary }}</strong></span>
          <span v-if="sourceHealthSummary()">数据源健康度 <strong>{{ sourceHealthSummary() }}</strong></span>
          <span v-if="item.preview?.targetId === 'INCIDENT_INVESTIGATION'">风险边界 <strong>范围与资源消耗较高；只读，不恢复</strong></span>
          <span>版本 <strong>v{{ item.preview?.previewVersion }}</strong></span>
          <span>有效期 <strong>{{ item.preview?.expiresAt ? new Date(item.preview.expiresAt).toLocaleString('zh-CN') : '—' }}</strong></span>
        </div>
        <p v-if="previewInput('truncated') === 'true'" class="scope-preview-warning">候选数量超过展示上限，请缩小时间或业务范围后重新发现。</p>
        <details v-if="isScopeDiscoveryPreview && scopeCandidates.length" class="scope-candidate-details">
          <summary>查看候选明细（{{ scopeCandidates.length }}）</summary>
          <div class="scope-candidate-list">
            <article v-for="(candidate, index) in scopeCandidates.slice(0, 50)" :key="`${candidateText(candidate, 'requestId')}-${index}`">
              <header><strong>{{ candidateText(candidate, 'orderNo') }}</strong><span>{{ candidateText(candidate, 'completeness') }}</span></header>
              <dl>
                <dt>requestId</dt><dd><code>{{ candidateText(candidate, 'requestId') }}</code></dd>
                <dt>deductNo</dt><dd><code>{{ candidateText(candidate, 'deductNo') }}</code></dd>
                <dt>deadLetterIds</dt><dd><code>{{ candidateText(candidate, 'deadLetterIds') }}</code></dd>
                <dt>queueNames</dt><dd><code>{{ candidateText(candidate, 'queueNames') }}</code></dd>
                <dt>纳入原因</dt><dd>{{ candidateText(candidate, 'inclusionReasons') }}</dd>
                <dt>关联质量</dt><dd>{{ candidateText(candidate, 'relationQuality') }}</dd>
              </dl>
            </article>
          </div>
          <p v-if="scopeCandidates.length > 50" class="scope-preview-warning">当前只展示前 50 条候选，请缩小范围查看其余记录。</p>
        </details>
        <div v-if="item.preview?.status === 'ACTIVE'" class="inline-actions">
          <button class="primary-button" type="button" :disabled="busy" @click="emit('confirmPreview', true)">确认并启动调查</button>
          <button v-if="isScopeDiscoveryPreview" class="secondary-button" type="button" :disabled="busy" @click="emit('supplyInput')">调整条件</button>
          <button class="secondary-button" type="button" :disabled="busy" @click="emit('confirmPreview', false)">取消</button>
        </div>
      </template>

      <template v-else-if="item.type === 'APPROVAL_REQUEST'">
        <p class="item-description">{{ item.content }}</p>
        <div class="approval-tool-name"><span>高风险工具</span><strong>{{ item.approval?.toolCallRequest.toolName }}</strong></div>
        <div class="inline-form-grid">
          <label>审批人<input :value="reviewer" @input="emit('update:reviewer', ($event.target as HTMLInputElement).value)" /></label>
          <label>审批意见<input :value="decisionReason" @input="emit('update:decisionReason', ($event.target as HTMLInputElement).value)" /></label>
        </div>
        <div class="inline-actions">
          <button class="primary-button" type="button" :disabled="busy" @click="emit('decideApproval', true)">批准并继续</button>
          <button class="secondary-button" type="button" :disabled="busy" @click="emit('decideApproval', false)">拒绝</button>
        </div>
      </template>

      <template v-else-if="item.type === 'FINAL_ANSWER'">
        <button class="copy-answer-button" type="button" aria-label="复制回答" @click="emit('copy', item.content)">复制</button>
        <div v-if="item.content" ref="markdownRoot" class="conversation-markdown" :class="{ streaming: item.answerState === 'STREAMING' }" v-html="markdown" />
        <div v-else class="answer-placeholder" :data-state="item.answerState">
          <i v-if="['WAITING', 'FINALIZING'].includes(item.answerState ?? '')" />
          <span>{{ answerStatus }}</span>
        </div>
      </template>

      <section v-else-if="item.type === 'ERROR'" class="workbench-error-card">
        <p>{{ item.content }}</p>
        <dl v-if="item.error">
          <dt>错误代码</dt><dd><code>{{ item.error.code }}</code></dd>
          <dt>可重试</dt><dd>{{ item.error.retryable ? '是' : '否' }}</dd>
          <dt v-if="item.error.correlationId || item.error.traceId">诊断标识</dt>
          <dd v-if="item.error.correlationId || item.error.traceId"><code>{{ item.error.correlationId || item.error.traceId }}</code></dd>
        </dl>
        <div class="inline-actions">
          <button v-if="item.error?.retryable" class="primary-button" type="button" :disabled="busy" @click="emit('retry')">重试任务</button>
          <button class="secondary-button" type="button" @click="emit('diagnostics')">查看诊断</button>
        </div>
      </section>
      <p v-else class="item-description">{{ item.content }}</p>
    </div>
  </article>
</template>
