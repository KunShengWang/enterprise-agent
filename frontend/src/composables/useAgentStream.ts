import { computed, ref } from 'vue'
import { agentApi } from '../api/agent'
import { ApiError } from '../api/http'
import { resumeAgentEvents, streamAgentEvents } from '../api/stream'
import type { AgentEvent, AgentRequest, AgentRunRecord, AgentStreamEvent } from '../types/agent'

const terminalTypes = new Set(['run_completed', 'run_failed', 'run_cancelled', 'transport_error'])

function fromStoredEvent(event: AgentEvent): AgentStreamEvent {
  return {
    eventId: event.eventId,
    traceId: event.runId,
    conversationId: event.sessionId,
    sequence: event.sequence,
    type: event.type.toLowerCase(),
    content: event.content,
    createdAt: event.createdAt,
    metadata: event.payload ?? {},
  }
}

export function useAgentStream() {
  const events = ref<AgentStreamEvent[]>([])
  const answer = ref('')
  const runId = ref('')
  const approvalId = ref('')
  const error = ref('')
  const running = ref(false)
  const connected = ref(false)
  const runRecord = ref<AgentRunRecord | null>(null)
  const hasModelDelta = ref(false)
  let controller: AbortController | null = null

  const lastEvent = computed(() => events.value.at(-1) ?? null)
  const persistedEvents = computed(() => events.value.filter((event) => event.metadata?.persisted !== false))

  function reset() {
    controller?.abort()
    controller = null
    events.value = []
    answer.value = ''
    runId.value = ''
    approvalId.value = ''
    error.value = ''
    running.value = false
    connected.value = false
    runRecord.value = null
    hasModelDelta.value = false
  }

  function acceptEvent(event: AgentStreamEvent) {
    connected.value = true
    if (event.traceId) {
      runId.value = event.traceId
    }
    if (event.type === 'approval_required') {
      approvalId.value = String(event.metadata.approvalId ?? '')
    }
    if (event.type === 'model_delta') {
      hasModelDelta.value = true
      answer.value += event.content
    }
    if (event.type === 'run_completed' && !hasModelDelta.value) {
      answer.value = event.content
    }
    if (event.type === 'run_failed' || event.type === 'transport_error' || event.type === 'stream_gap') {
      error.value = event.content
    }

    const existingIndex = events.value.findIndex((item) => item.eventId === event.eventId)
    if (existingIndex >= 0) {
      events.value.splice(existingIndex, 1, event)
    } else {
      events.value.push(event)
    }
    events.value.sort((left, right) => {
      if (left.sequence === right.sequence) {
        return new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()
      }
      return left.sequence - right.sequence
    })
  }

  async function refreshStoredRun() {
    if (!runId.value) return
    try {
      const [record, storedEvents] = await Promise.all([
        agentApi.findRun(runId.value),
        agentApi.runEvents(runId.value),
      ])
      runRecord.value = record
      approvalId.value = record.state === 'WAITING_APPROVAL' ? record.approvalId : ''
      if (record.answer) {
        answer.value = record.answer
      }
      const heartbeatEvents = events.value.filter((event) => event.metadata?.persisted === false)
      events.value = [...storedEvents.map(fromStoredEvent), ...heartbeatEvents].sort((left, right) => {
        if (left.sequence === right.sequence) {
          return new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()
        }
        return left.sequence - right.sequence
      })
    } catch {
      // 流事件仍然可用于学习；数据库投影刷新失败不覆盖主错误。
    }
  }

  async function consume(streamer: (signal: AbortSignal) => Promise<void>) {
    if (running.value) return
    error.value = ''
    running.value = true
    connected.value = false
    controller = new AbortController()

    try {
      await streamer(controller.signal)
    } catch (reason) {
      if (reason instanceof DOMException && reason.name === 'AbortError') {
        error.value = '已停止接收事件，并向 Runtime 请求取消当前 Run。'
      } else if (reason instanceof ApiError) {
        error.value = `${reason.code}: ${reason.message}`
      } else {
        error.value = reason instanceof Error ? reason.message : 'SSE 连接发生未知错误'
      }
    } finally {
      running.value = false
      connected.value = false
      controller = null
      await refreshStoredRun()
    }
  }

  async function start(request: AgentRequest) {
    reset()
    await consume((signal) => streamAgentEvents(request, {
      signal,
      onEvent: acceptEvent,
    }))
  }

  async function resume() {
    if (!runId.value) {
      error.value = '当前没有可以恢复的 Run。'
      return
    }
    if (runRecord.value?.state === 'WAITING_APPROVAL') {
      answer.value = ''
    }
    await consume((signal) => resumeAgentEvents(runId.value, {
      signal,
      onEvent: acceptEvent,
    }))
  }

  async function cancel() {
    const activeRunId = runId.value
    if (activeRunId) {
      try {
        await agentApi.cancelRun(activeRunId)
      } catch {
        // 中断 SSE 本身也会触发服务端取消，显式接口失败时继续关闭连接。
      }
    }
    controller?.abort()
  }

  async function hydrate(run: AgentRunRecord, storedEvents: AgentEvent[]) {
    reset()
    runId.value = run.runId
    runRecord.value = run
    approvalId.value = run.approvalId
    answer.value = run.answer
    events.value = storedEvents.map(fromStoredEvent)
    hasModelDelta.value = events.value.some((event) => event.type === 'model_delta')
  }

  async function refresh() {
    await refreshStoredRun()
  }

  const terminal = computed(() => Boolean(lastEvent.value && terminalTypes.has(lastEvent.value.type)))

  return {
    events,
    persistedEvents,
    answer,
    runId,
    approvalId,
    error,
    running,
    connected,
    runRecord,
    lastEvent,
    terminal,
    start,
    resume,
    cancel,
    reset,
    refresh,
    hydrate,
  }
}
