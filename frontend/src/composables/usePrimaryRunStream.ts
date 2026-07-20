import { ref } from 'vue'
import { workbenchApi } from '../api/workbench'
import type { WorkEvent, WorkItemDetail, WorkStreamItem } from '../types/workbench'
import type { EventSourceLike } from './usePresentationStream'

type StreamState = 'idle' | 'connecting' | 'live' | 'recovering' | 'error'

export interface PrimaryRunStreamOptions {
  onDelta: (event: WorkStreamItem) => boolean
  onTerminal: (state: 'COMPLETED' | 'FAILED' | 'CANCELLED') => void
  onSourceChanged?: (event: WorkStreamItem) => void
  onReplayStart?: () => void
  expectedRunId: () => string
  history?: typeof workbenchApi.events
  streamUrl?: typeof workbenchApi.streamUrl
  eventSourceFactory?: (url: string) => EventSourceLike
  reconnectDelayMs?: number
}

export function usePrimaryRunStream(options: PrimaryRunStreamOptions) {
  const rawEvents = ref<WorkEvent[]>([])
  const workCursor = ref(-1)
  const runCursor = ref(-1)
  const connectionState = ref<StreamState>('idle')
  const reconnectCount = ref(0)
  const lastEventAt = ref('')
  const gap = ref(false)
  const syncError = ref('')
  const seenWorkEventIds = new Set<string>()
  const seenRunEventIds = new Set<string>()

  const eventSourceFactory = options.eventSourceFactory ?? (url => new EventSource(url))
  const history = options.history ?? workbenchApi.events
  const streamUrl = options.streamUrl ?? workbenchApi.streamUrl
  const reconnectDelayMs = options.reconnectDelayMs ?? 1200
  let activeWorkItemId = ''
  let generation = 0
  let source: EventSourceLike | null = null
  let reconnectTimer = 0

  function closeSource() {
    source?.close()
    source = null
    window.clearTimeout(reconnectTimer)
  }

  function parse(raw: Event) {
    try { return JSON.parse((raw as MessageEvent<string>).data) as WorkStreamItem }
    catch { syncError.value = 'Unified SSE returned invalid data'; return null }
  }

  function applyResumeToken(token: string) {
    const match = /^w:(-?\d+);r:(-?\d+)$/.exec(token)
    if (!match) return
    workCursor.value = Math.max(workCursor.value, Number(match[1]))
    runCursor.value = Math.max(runCursor.value, Number(match[2]))
  }

  function terminalState(event: WorkStreamItem) {
    const phase = String(event.payload.runtimeEventType ?? event.payload.phase ?? event.eventType).toUpperCase()
    if (phase.includes('RUN_COMPLETED')) return 'COMPLETED' as const
    if (phase.includes('RUN_FAILED')) return 'FAILED' as const
    if (phase.includes('RUN_CANCELLED') || phase.includes('WORK_ITEM_CANCELLED')) return 'CANCELLED' as const
    return null
  }

  function open(workItemId: string, token: number) {
    if (token !== generation || workItemId !== activeWorkItemId) return
    connectionState.value = 'connecting'
    const next = eventSourceFactory(streamUrl(workItemId, workCursor.value, runCursor.value))
    source = next
    next.onopen = () => { if (token === generation) connectionState.value = 'live' }
    next.addEventListener('work-event', raw => {
      if (token !== generation || workItemId !== activeWorkItemId) return
      const event = parse(raw)
      if (!event) return
      if (event.workSequence > workCursor.value + 1) {
        gap.value = true
        void recover(workItemId, token)
        return
      }
      applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
      if (!event.eventId || seenWorkEventIds.has(event.eventId)) return
      seenWorkEventIds.add(event.eventId)
      rawEvents.value.push({
        eventId: event.eventId, workItemId, sequence: event.workSequence, eventType: event.eventType,
        phase: String(event.payload.runtimeEventType ?? event.payload.incidentEventType
          ?? event.payload.recoveryPlanEventType ?? event.payload.phase ?? event.eventType),
        summary: event.content, projectedAt: event.createdAt, sourceType: event.sourceType,
        sourceId: event.sourceId, sourceSequence: event.sourceSequence,
        sourceCreatedAt: event.createdAt, payload: event.payload,
        correlationId: String(event.payload.correlationId ?? ''),
        causationId: String(event.payload.causationId ?? ''),
      })
      lastEventAt.value = event.createdAt
      const expected = options.expectedRunId()
      const terminal = event.sourceType === 'AGENT_RUN' && expected && event.sourceId !== expected
        ? null : terminalState(event)
      if (terminal) options.onTerminal(terminal)
      options.onSourceChanged?.(event)
    })
    next.addEventListener('model-delta', raw => {
      if (token !== generation || workItemId !== activeWorkItemId) return
      const event = parse(raw)
      if (!event) return
      applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
      if (!event.eventId || seenRunEventIds.has(event.eventId)) return
      const expected = options.expectedRunId()
      if (expected && event.sourceId !== expected) return
      seenRunEventIds.add(event.eventId)
      if (options.onDelta(event)) lastEventAt.value = event.createdAt
    })
    next.addEventListener('heartbeat', raw => {
      if (token !== generation) return
      const event = parse(raw)
      if (event) applyResumeToken(event.resumeToken || (raw as MessageEvent<string>).lastEventId)
    })
    next.addEventListener('gap', () => {
      if (token !== generation) return
      gap.value = true
      void recover(workItemId, token)
    })
    next.addEventListener('sync-error', () => {
      if (token !== generation) return
      syncError.value = 'Unified stream sync error'
      void recover(workItemId, token)
    })
    next.onerror = () => {
      if (token !== generation || workItemId !== activeWorkItemId) return
      connectionState.value = 'connecting'
      reconnectCount.value += 1
      closeSource()
      reconnectTimer = window.setTimeout(() => open(workItemId, token), reconnectDelayMs)
    }
  }

  async function recover(workItemId: string, token: number) {
    closeSource()
    connectionState.value = 'recovering'
    try {
      const historical: WorkEvent[] = []
      let cursor = -1
      for (;;) {
        const page = await history(workItemId, cursor, 500)
        if (!page.length) break
        historical.push(...page)
        cursor = page.at(-1)?.sequence ?? cursor
        if (page.length < 500) break
      }
      if (token !== generation || workItemId !== activeWorkItemId) return
      rawEvents.value = historical
      seenWorkEventIds.clear()
      historical.forEach(event => seenWorkEventIds.add(event.eventId))
      workCursor.value = historical.at(-1)?.sequence ?? -1
      runCursor.value = -1
      seenRunEventIds.clear()
      options.onReplayStart?.()
      gap.value = false
      syncError.value = ''
      open(workItemId, token)
    } catch (cause) {
      if (token !== generation) return
      connectionState.value = 'error'
      syncError.value = cause instanceof Error ? cause.message : 'Unified stream recovery failed'
    }
  }

  function start(detail: WorkItemDetail) {
    stop()
    activeWorkItemId = detail.workItem.workItemId
    const token = ++generation
    reconnectCount.value = 0
    rawEvents.value = [...detail.events].sort((left, right) => left.sequence - right.sequence)
    rawEvents.value.forEach(event => seenWorkEventIds.add(event.eventId))
    workCursor.value = rawEvents.value.at(-1)?.sequence ?? -1
    open(activeWorkItemId, token)
  }

  function stop() {
    generation += 1
    closeSource()
    activeWorkItemId = ''
    rawEvents.value = []
    workCursor.value = -1
    runCursor.value = -1
    connectionState.value = 'idle'
    lastEventAt.value = ''
    gap.value = false
    syncError.value = ''
    seenWorkEventIds.clear()
    seenRunEventIds.clear()
    reconnectCount.value = 0
  }

  return {
    rawEvents, workCursor, runCursor, connectionState, reconnectCount, lastEventAt,
    gap, syncError, seenWorkEventIds, seenRunEventIds, start, stop,
    activeWorkItemId: () => activeWorkItemId,
  }
}
