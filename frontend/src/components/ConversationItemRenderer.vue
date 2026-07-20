<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '../utils/markdown'
import type { ConversationItem } from '../types/conversation'

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
  'update:reviewer': [value: string]
  'update:decisionReason': [value: string]
}>()

const markdown = computed(() => renderMarkdown(props.item.content))
const itemIcon = computed(() => ({
  USER_MESSAGE: '你', AGENT_STATUS: 'A', TASK_PLAN: '≡', ROUTE_SUMMARY: 'A',
  TOOL_CALL: '⌘', TOOL_RESULT: '⌘', AGENT_DELEGATION: '↳', INCIDENT_PREVIEW: '!',
  APPROVAL_REQUEST: '✓', FINAL_ANSWER: 'A', ERROR: '!',
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

const answerStatus = computed(() => ({
  WAITING: '等待模型输出', STREAMING: '正在生成', FINALIZING: '正在确认最终结果',
  COMPLETED: '已完成', FAILED: '执行失败', CANCELLED: '已取消', IDLE: '',
} as Record<string, string>)[props.item.answerState ?? 'IDLE'])
const collapsibleSummary = computed(() => ['TASK_UNDERSTANDING', 'ROUTE_SUMMARY']
  .includes(props.item.presentationKind ?? ''))
</script>

<template>
  <article class="conversation-item" :data-type="item.type.toLowerCase()" :data-status="item.status" :data-presentation="item.presentationKind?.toLowerCase()">
    <div class="conversation-item-icon">{{ itemIcon }}</div>
    <div class="conversation-item-body">
      <header v-if="item.type !== 'USER_MESSAGE' && !collapsibleSummary">
        <div><strong>{{ item.title }}</strong><span v-if="item.live" class="live-label">{{ answerStatus || '正在生成' }}</span></div>
        <small v-if="!['FINAL_ANSWER', 'ROUTE_SUMMARY'].includes(item.type)" class="item-state"><i />{{ statusLabel(item.status) }}</small>
      </header>

      <p v-if="item.type === 'USER_MESSAGE'" class="user-message-text">{{ item.content }}</p>

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

      <template v-else-if="item.type === 'INCIDENT_PREVIEW'">
        <p class="item-description">{{ item.content }}</p>
        <div class="preview-facts">
          <span>目标 <strong>{{ item.preview?.targetId }}</strong></span>
          <span>版本 <strong>v{{ item.preview?.previewVersion }}</strong></span>
          <span>有效期 <strong>{{ item.preview?.expiresAt ? new Date(item.preview.expiresAt).toLocaleString('zh-CN') : '—' }}</strong></span>
        </div>
        <div v-if="item.preview?.status === 'ACTIVE'" class="inline-actions">
          <button class="primary-button" type="button" :disabled="busy" @click="emit('confirmPreview', true)">确认并启动</button>
          <button class="secondary-button" type="button" :disabled="busy" @click="emit('confirmPreview', false)">拒绝</button>
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
        <button class="copy-answer-button" type="button" title="复制回答" @click="emit('copy', item.content)">复制</button>
        <div v-if="item.content" class="conversation-markdown" :class="{ streaming: item.answerState === 'STREAMING' }" v-html="markdown" />
        <div v-else class="answer-placeholder" :data-state="item.answerState">
          <i v-if="['WAITING', 'FINALIZING'].includes(item.answerState ?? '')" />
          <span>{{ answerStatus }}</span>
        </div>
      </template>

      <p v-else-if="item.type === 'ERROR'" class="error-copy">{{ item.content }}</p>
      <p v-else class="item-description">{{ item.content }}</p>
    </div>
  </article>
</template>
