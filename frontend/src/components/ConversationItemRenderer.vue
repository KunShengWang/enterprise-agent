<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { UserRound, Bot, ListChecks, Wrench, GitBranch, ShieldCheck, CircleAlert, ClipboardList, FileText, Copy } from '@lucide/vue'
import StreamingAnswer from './StreamingAnswer.vue'
import ToolCallRow from './ToolCallRow.vue'
import { useAnswerPresentation } from '../composables/useAnswerPresentation'
import { procurementTools, publicApprovalScope } from '../utils/procurementPresentation'
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

const { displayed, revealing } = useAnswerPresentation(
  () => props.item.type === 'FINAL_ANSWER' ? props.item.content : '',
  () => props.item.answerState === 'STREAMING' && props.item.animate !== false && !props.item.readOnly,
  () => props.item.id,
  undefined,
  () => ['FINALIZING', 'COMPLETED'].includes(props.item.answerState ?? ''),
)
const streaming = computed(() => props.item.answerState === 'STREAMING' || revealing.value)
const approvalScope = computed(() => publicApprovalScope(props.item.approval?.toolCallRequest.arguments ?? {}))
const pendingApproval = computed(() => props.item.approval?.status === 'REQUESTED')
const approvalRecordLabel = computed(() => pendingApproval.value
  ? props.item.readOnly || props.item.status === 'unresolved'
    ? '此审批请求已随任务结束，结果待核对。原始审批记录仍为 REQUESTED，可在运行诊断中核对。'
    : '等待后端审批决定。批准后才会继续工具执行，RFQ 创建结果需单独确认。'
  : `审批记录：${props.item.approval?.status === 'APPROVED' ? '已批准' : props.item.approval?.status === 'REJECTED' ? '已拒绝' : props.item.approval?.status}`)
const itemIcon = computed(() => ({
  USER_MESSAGE: UserRound, AGENT_STATUS: Bot, TASK_PLAN: ListChecks, ROUTE_SUMMARY: Bot,
  TOOL_CALL: Wrench, TOOL_RESULT: Wrench, AGENT_DELEGATION: GitBranch, ROUTE_PREVIEW: CircleAlert,
  APPROVAL_REQUEST: ShieldCheck, FINAL_ANSWER: Bot, ERROR: CircleAlert, EXECUTION_NARRATIVE: ClipboardList,
}[props.item.type]))

function statusLabel(status: string) {
  return ({ pending: '未开始', active: '执行中', completed: '已完成', failed: '失败', waiting: '等待操作', unresolved: '结果待核对' } as Record<string, string>)[status] ?? status
}

function attachmentSize(size: number) {
  return size < 1024 ? `${size} B` : `${Math.ceil(size / 1024)} KB`
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
watch(() => [displayed.value, streaming.value], decorateCodeBlocks, { immediate: true })
watch(() => props.item.id, () => { expandedNarrativeId.value = defaultExpandedNarrative() }, { immediate: true })
watch(() => props.item.narrative?.items.map(entry => entry.status).join(','), () => {
  if (!expandedNarrativeId.value) expandedNarrativeId.value = defaultExpandedNarrative()
})
</script>

<template>
  <ToolCallRow v-if="item.tool && ['TOOL_CALL', 'TOOL_RESULT'].includes(item.type)" :item="item" @diagnostics="emit('diagnostics')" />
  <article v-else class="conversation-item" :data-type="item.type.toLowerCase()" :data-status="item.status" :data-presentation="item.presentationKind?.toLowerCase()">
    <div class="conversation-item-icon"><component :is="itemIcon" :size="17" aria-hidden="true" /></div>
    <div class="conversation-item-body">
      <header v-if="item.type !== 'USER_MESSAGE' && !collapsibleSummary">
        <div><strong>{{ item.type === 'FINAL_ANSWER' ? '采购助手' : item.title }}</strong><span v-if="item.live || revealing" class="live-label">{{ item.answerState === 'COMPLETED' && revealing ? '正在显示回答' : answerStatus || '正在生成' }}</span></div>
        <small v-if="!['FINAL_ANSWER', 'ROUTE_SUMMARY'].includes(item.type)" class="item-state"><i />{{ statusLabel(item.status) }}</small>
      </header>

      <template v-if="item.type === 'USER_MESSAGE'">
        <p class="user-message-text">{{ item.content }}</p>
        <div v-if="item.attachments?.length" class="message-attachments" aria-label="本次消息的附件">
          <span v-for="attachment in item.attachments" :key="`${attachment.name}-${attachment.size}`">
            <FileText :size="16" aria-hidden="true" /><span><strong>{{ attachment.name }}</strong><small>{{ attachmentSize(attachment.size) }}</small></span>
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

      <template v-else-if="item.type === 'AGENT_DELEGATION'">
        <p class="item-description">{{ item.content }}</p>
      </template>

      <template v-else-if="item.type === 'EXECUTION_NARRATIVE' && item.narrative">
        <ol class="execution-narrative-list">
          <li v-for="entry in item.narrative.items" :key="entry.id" :data-status="entry.status">
            <i aria-hidden="true" />
            <div class="execution-narrative-entry">
              <!-- 收起态只保留一行：状态点 + 摘要。detail 与操作入口都移入展开区，
                   否则每条记录占 4 行，一屏放不下几次执行。 -->
              <div class="execution-narrative-heading">
                <button class="execution-narrative-toggle" type="button" :aria-expanded="expandedNarrativeId === entry.id" @click="toggleNarrative(entry.id)">
                  <strong>{{ entry.summary }}</strong>
                </button>
              </div>
              <section v-if="expandedNarrativeId === entry.id" class="execution-narrative-detail">
                <p v-if="entry.detail" class="execution-narrative-lead">{{ entry.detail }}</p>
                <dl>
                  <template v-for="field in entry.metadata" :key="`${entry.id}-${field.label}`">
                    <dt>{{ field.label }}</dt><dd :class="{ code: field.code }">{{ field.value }}</dd>
                  </template>
                </dl>
                <div v-if="entry.findings.length" class="execution-findings">
                  <strong>关键发现</strong><ul><li v-for="finding in entry.findings" :key="finding">{{ finding }}</li></ul>
                </div>
                <div v-if="entry.detail" class="execution-output-summary"><strong>公开输出摘要</strong><p>{{ entry.detail }}</p></div>
                <button class="execution-inspector-link" type="button" @click.stop="emit('locatePresentations', { turnId: item.narrative!.turnId, presentationIds: entry.sourcePresentationIds })">在检查器中打开</button>
              </section>
            </div>
          </li>
        </ol>
      </template>

      <template v-else-if="item.presentationKind === 'WAITING_FOR_USER'">
        <p class="item-description">{{ item.content }}</p>
        <ul class="clarification-list"><li v-for="step in item.steps" :key="step">{{ step }}</li></ul>
        <div v-if="!item.readOnly" class="inline-actions"><button class="primary-button" type="button" @click="emit('supplyInput')">补充信息</button></div>
      </template>

      <template v-else-if="item.type === 'ROUTE_PREVIEW'">
        <p>{{ item.readOnly ? '业务已退役、不能继续执行。以下为已保存的预览。' : item.content }}</p>
        <dl><dt>执行目标</dt><dd>{{ item.preview?.targetId }}</dd><dt>版本</dt><dd>{{ item.preview?.previewVersion }}</dd><dt>状态</dt><dd>{{ item.preview?.status }}</dd></dl>
        <div v-if="!item.readOnly && item.preview?.status === 'ACTIVE'" class="inline-actions">
          <button class="primary-button" type="button" :disabled="busy" @click="emit('confirmPreview', true)">确认执行范围</button>
          <button class="secondary-button" type="button" :disabled="busy" @click="emit('confirmPreview', false)">取消</button>
        </div>
      </template>

      <template v-else-if="item.type === 'APPROVAL_REQUEST'">
        <p class="item-description">{{ item.approval?.reason || item.content }}</p>
        <div class="approval-tool-name"><span>申请操作</span><strong>{{ procurementTools[item.approval?.toolCallRequest.toolName ?? '']?.label || item.approval?.toolCallRequest.toolName }}</strong></div>
        <section class="approval-scope"><strong>影响范围</strong><dl><template v-for="field in approvalScope" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl><p v-if="!approvalScope.length">审批记录未提供可公开的业务字段，可在诊断中核对。</p><p>{{ approvalRecordLabel }}</p></section>
        <div v-if="!item.readOnly && pendingApproval" class="inline-form-grid">
          <label>审批人<input :value="reviewer" @input="emit('update:reviewer', ($event.target as HTMLInputElement).value)" /></label>
          <label>审批意见<input :value="decisionReason" @input="emit('update:decisionReason', ($event.target as HTMLInputElement).value)" /></label>
        </div>
        <div v-if="!item.readOnly && pendingApproval" class="inline-actions">
          <button class="primary-button" type="button" :disabled="busy" @click="emit('decideApproval', true)">批准并继续</button>
          <button class="secondary-button" type="button" :disabled="busy" @click="emit('decideApproval', false)">拒绝</button>
        </div>
      </template>

      <template v-else-if="item.type === 'FINAL_ANSWER'">
        <button class="copy-answer-button" type="button" aria-label="复制回答" @click="emit('copy', item.content)"><Copy :size="14" />复制</button>
        <div v-if="item.content" ref="markdownRoot"><StreamingAnswer :content="displayed" :streaming="streaming" /></div>
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
        <div v-if="!item.readOnly" class="inline-actions">
          <button v-if="item.error?.retryable" class="primary-button" type="button" :disabled="busy" @click="emit('retry')">重试任务</button>
          <button class="secondary-button" type="button" @click="emit('diagnostics')">查看诊断</button>
        </div>
      </section>
      <p v-else class="item-description">{{ item.content }}</p>
    </div>
  </article>
</template>
