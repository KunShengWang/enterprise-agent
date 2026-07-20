<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { workbenchApi } from '../api/workbench'
import type { WorkFocus, WorkInput, WorkItem, WorkItemDetail, WorkLink } from '../types/workbench'

const conversationId = ref(localStorage.getItem('unified-workbench-conversation') || `workbench-${new Date().toISOString().slice(0, 10)}`)
const content = ref('')
const inputs = ref<WorkInput[]>([])
const workItems = ref<WorkItem[]>([])
const focus = ref<WorkFocus | null>(null)
const selectedId = ref('')
const detail = ref<WorkItemDetail | null>(null)
const busy = ref(false)
const error = ref('')
let timer = 0

const selected = computed(() => workItems.value.find(item => item.workItemId === selectedId.value) ?? null)
const routeReason = computed(() => String(detail.value?.routingDecision?.decision?.reason ?? '等待 Router 形成可审计决策'))

async function refresh() {
  try {
    const [nextInputs, nextItems] = await Promise.all([
      workbenchApi.inputs(conversationId.value), workbenchApi.workItems(conversationId.value),
    ])
    inputs.value = nextInputs
    workItems.value = nextItems
    try { focus.value = await workbenchApi.focus(conversationId.value) } catch { focus.value = null }
    if (!selectedId.value) selectedId.value = focus.value?.focusedWorkItemId || nextItems[0]?.workItemId || ''
    if (selectedId.value) detail.value = await workbenchApi.detail(selectedId.value)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '统一工作台加载失败'
  }
}

async function submit() {
  if (!content.value.trim() || busy.value) return
  busy.value = true; error.value = ''
  localStorage.setItem('unified-workbench-conversation', conversationId.value)
  try {
    const result = await workbenchApi.submit(conversationId.value, content.value.trim())
    content.value = ''
    if (result.workItemId) selectedId.value = result.workItemId
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提交失败' }
  finally { busy.value = false }
}

async function choose(item: WorkItem) { selectedId.value = item.workItemId; detail.value = await workbenchApi.detail(item.workItemId) }
async function makeFocus(item: WorkItem) { if (!focus.value) return; await workbenchApi.switchFocus(conversationId.value, item.workItemId, focus.value.version); await refresh() }
async function decide(approved: boolean) {
  const preview = detail.value?.preview
  if (!preview || !selected.value) return
  busy.value = true; error.value = ''
  try { approved ? await workbenchApi.confirm(selected.value.workItemId, preview) : await workbenchApi.reject(selected.value.workItemId, preview.previewId); await refresh() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '确认失败' }
  finally { busy.value = false }
}
function targetRoute(link: WorkLink) {
  if (link.linkType === 'INCIDENT') return { path: '/incident-command', query: { incidentId: link.linkedId } }
  if (link.linkType === 'RECOVERY_PLAN') {
    const typedPayload = detail.value?.routingDecision?.validation?.typedPayload as Record<string, unknown> | undefined
    const incidentId = detail.value?.workItem.activeIncidentId || String(typedPayload?.incidentId ?? '')
    return { path: '/incident-command', query: { incidentId, planId: link.linkedId } }
  }
  return { path: '/', query: { runId: link.linkedId } }
}
function stateClass(state: string) {
  if (['DISPATCHED', 'CLOSED'].includes(state)) return 'work-ok'
  if (['MANUAL_REVIEW', 'ABANDONED'].includes(state)) return 'work-bad'
  return 'work-active'
}

onMounted(() => { refresh(); timer = window.setInterval(refresh, 1500) })
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div class="unified-workbench">
    <aside class="panel unified-history">
      <p class="eyebrow">UNIFIED AGENT WORKBENCH · M1</p>
      <h2>一个入口，四种执行目标</h2>
      <label class="field-label">Conversation ID</label>
      <input v-model="conversationId" @change="refresh" />
      <div class="unified-task-list">
        <button v-for="item in workItems" :key="item.workItemId" :class="{ selected: item.workItemId === selectedId }" @click="choose(item)">
          <span :class="stateClass(item.controlState)" />
          <strong>{{ item.originalGoal }}</strong>
          <small>{{ item.activeExecutionTarget || 'ROUTING' }} · {{ item.controlState }}</small>
          <code>{{ item.workItemId }}</code>
        </button>
        <p v-if="!workItems.length" class="compact-empty">还没有任务。直接描述目标，系统会选择执行方式。</p>
      </div>
    </aside>

    <main class="panel unified-main">
      <header class="unified-header">
        <div><p class="eyebrow">GOAL ROUTING</p><h2>{{ selected?.originalGoal || '描述你想完成的目标' }}</h2></div>
        <div v-if="selected" class="action-row">
          <button v-if="focus && focus.focusedWorkItemId !== selected.workItemId" class="secondary-button" @click="makeFocus(selected)">设为当前任务</button>
          <span class="status-badge"><i />{{ selected.controlState }}</span>
        </div>
      </header>

      <section v-if="detail" class="unified-detail">
        <article class="route-card">
          <div><span>系统选择</span><strong>{{ detail.workItem.activeExecutionTarget || '正在路由' }}</strong></div>
          <p>{{ routeReason }}</p>
          <div class="route-meta"><code>{{ detail.routingDecision?.decisionId || detail.workItem.routingFailureCode || 'routing pending' }}</code><span>{{ detail.workItem.executionState }}</span></div>
        </article>

        <article v-if="detail.preview" class="preview-card">
          <div><p class="eyebrow">EXPLICIT CONFIRMATION</p><h3>启动事故调查前确认范围</h3></div>
          <p>Preview {{ detail.preview.previewId }} · v{{ detail.preview.previewVersion }} · {{ detail.preview.status }}</p>
          <pre>{{ JSON.stringify(detail.preview.payload, null, 2) }}</pre>
          <div v-if="detail.preview.status === 'ACTIVE'" class="action-row">
            <button class="primary-button" :disabled="busy" @click="decide(true)">确认并启动</button>
            <button class="danger-button" :disabled="busy" @click="decide(false)">拒绝</button>
          </div>
        </article>

        <article v-if="detail.links.length" class="target-links">
          <p class="eyebrow">EXECUTION TARGET</p>
          <RouterLink v-for="link in detail.links" :key="link.linkedId" :to="targetRoute(link)">
            <strong>{{ link.linkType }}</strong><code>{{ link.linkedId }}</code><span>打开专项视图 →</span>
          </RouterLink>
        </article>

        <article class="local-events">
          <div><h3>M1 本地执行时间线</h3><small>跨源 Run / Incident 投影将在 M2 接入</small></div>
          <ol>
            <li v-for="event in detail.events" :key="event.eventId">
              <span :class="stateClass(event.phase)" /><div><strong>{{ event.summary }}</strong><code>#{{ event.sequence }} · {{ event.eventType }} · {{ event.phase }}</code></div>
            </li>
          </ol>
        </article>
      </section>

      <section v-else class="unified-empty"><strong>无需先选择页面</strong><p>普通问答、OrderCare 单案例、事故调查和恢复计划都从同一个输入框开始。</p></section>

      <footer class="unified-composer">
        <textarea v-model="content" rows="3" placeholder="例如：调查批次 BATCH-20260720-01 的异常订单与死信，并说明是否需要人工确认" @keydown.ctrl.enter.prevent="submit" />
        <div><span v-if="error" class="inline-error">{{ error }}</span><small>Ctrl + Enter 发送 · 身份由服务端注入</small><button class="primary-button" :disabled="busy || !content.trim()" @click="submit">{{ busy ? '处理中…' : '发送目标' }}</button></div>
      </footer>
    </main>
  </div>
</template>
