<script setup lang="ts">
import { ref } from 'vue'

import type { ComposerAttachment } from '../types/workbench'

const props = defineProps<{
  modelValue: string
  busy: boolean
  stopAvailable?: boolean
  stopping?: boolean
  error: string
  waitingForInput?: boolean
  controlMode?: 'idle' | 'running' | 'pausing' | 'paused' | 'cancelling' | 'waiting'
  attachments?: ComposerAttachment[]
}>()
const emit = defineEmits<{
  submit: []
  stop: []
  'update:modelValue': [value: string]
  'update:attachments': [value: ComposerAttachment[]]
}>()
const input = ref<HTMLTextAreaElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const readingFiles = ref(false)
const attachmentError = ref('')
const allowedExtensions = new Set([
  'txt', 'md', 'markdown', 'json', 'yaml', 'yml', 'xml', 'csv', 'log',
  'java', 'kt', 'kts', 'js', 'jsx', 'ts', 'tsx', 'vue', 'css', 'scss',
  'html', 'sql', 'sh', 'ps1', 'py', 'go', 'rs', 'properties', 'gradle',
])
const maxFiles = 3
const maxFileBytes = 32 * 1024

function openFilePicker() {
  attachmentError.value = ''
  fileInput.value?.click()
}

async function addFiles(event: Event) {
  const element = event.target as HTMLInputElement
  const selected = [...(element.files ?? [])]
  element.value = ''
  if (!selected.length) return
  const current = props.attachments ?? []
  if (current.length + selected.length > maxFiles) {
    attachmentError.value = `最多上传 ${maxFiles} 个文本附件。`
    return
  }
  readingFiles.value = true
  try {
    const additions: ComposerAttachment[] = []
    for (const file of selected) {
      const extension = file.name.includes('.') ? file.name.split('.').pop()!.toLowerCase() : ''
      if ((!file.type.startsWith('text/') && !allowedExtensions.has(extension)) || file.size > maxFileBytes) {
        attachmentError.value = `${file.name} 不是受支持的文本文件，或超过 32 KB。`
        continue
      }
      additions.push({
        id: crypto.randomUUID(),
        name: file.name,
        size: file.size,
        mediaType: file.type || 'text/plain',
        content: await file.text(),
      })
    }
    if (additions.length) emit('update:attachments', [...current, ...additions])
  } finally {
    readingFiles.value = false
  }
}

function removeAttachment(id: string) {
  emit('update:attachments', (props.attachments ?? []).filter(item => item.id !== id))
}

function sizeLabel(size: number) {
  return size < 1024 ? `${size} B` : `${Math.ceil(size / 1024)} KB`
}

defineExpose({ focus: () => input.value?.focus() })
</script>

<template>
  <footer class="task-composer">
    <div class="composer-surface">
      <div v-if="attachments?.length" class="composer-attachments" aria-label="已选择的附件">
        <span v-for="attachment in attachments" :key="attachment.id" class="composer-attachment">
          <span aria-hidden="true">▤</span>
          <span><strong>{{ attachment.name }}</strong><small>{{ sizeLabel(attachment.size) }}</small></span>
          <button type="button" :aria-label="`移除附件 ${attachment.name}`" @click="removeAttachment(attachment.id)">×</button>
        </span>
      </div>
      <textarea
        ref="input"
        :value="modelValue"
        rows="2"
        aria-label="任务目标或补充要求"
        :placeholder="waitingForInput ? '补充大致时间、订单号或明确的业务异常现象…' : '描述目标，或为当前任务补充要求…'"
        @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
        @keydown.ctrl.enter.prevent="emit('submit')"
      />
      <div class="composer-toolbar">
        <div class="composer-tools">
          <input ref="fileInput" class="composer-file-input" type="file" multiple accept=".txt,.md,.markdown,.json,.yaml,.yml,.xml,.csv,.log,.java,.kt,.kts,.js,.jsx,.ts,.tsx,.vue,.css,.scss,.html,.sql,.sh,.ps1,.py,.go,.rs,.properties,.gradle,text/*" @change="addFiles" />
          <button class="composer-icon-button" type="button" aria-label="上传文本附件" title="上传文本或代码附件（最多 3 个，每个 32 KB）" :disabled="readingFiles || busy" @click="openFilePicker">＋</button>
          <span v-if="stopping || controlMode === 'cancelling'" class="composer-running-label pending">正在终止任务</span>
          <span v-else-if="controlMode === 'running'" class="composer-running-label"><i />Agent 正在执行</span>
          <span v-else-if="controlMode === 'pausing'" class="composer-running-label pending">正在处理任务</span>
          <span v-else-if="controlMode === 'paused'" class="composer-running-label paused">任务已暂停</span>
        </div>
        <div class="composer-actions">
          <button v-if="stopAvailable" class="composer-submit-button stop" type="button" aria-label="终止当前任务" title="终止任务" :disabled="stopping" @click="emit('stop')">■</button>
          <button v-else class="composer-submit-button" type="button" aria-label="发送并开始执行" title="发送" :disabled="busy || readingFiles || !modelValue.trim()" @click="emit('submit')">↑</button>
        </div>
      </div>
    </div>
    <p>
      <span v-if="error" class="composer-error">{{ error }}</span>
      <span v-else-if="attachmentError" class="composer-error">{{ attachmentError }}</span>
      <span v-else-if="waitingForInput">提交后将继续当前任务，不会新建事故调查。</span>
      <span v-else>Ctrl + Enter 发送 · 支持最多 3 个、每个 32 KB 的文本或代码附件</span>
    </p>
  </footer>
</template>
