import { ref } from 'vue'
import { workbenchApi } from '../api/workbench'
import type { PublicPresentation } from '../types/workbench'

type StreamState = 'idle' | 'connecting' | 'live' | 'recovering' | 'error'

export interface EventSourceLike {
  close(): void
  onopen: ((event: Event) => void) | null
  onerror: ((event: Event) => void) | null
  addEventListener(type: string, listener: EventListener): void
}

export interface PresentationStreamDependencies {
  history?: typeof workbenchApi.presentations
  inspectorHistory?: typeof workbenchApi.inspectorPresentations
  streamUrl?: typeof workbenchApi.presentationStreamUrl
  eventSourceFactory?: (url: string) => EventSourceLike
  reconnectDelayMs?: number
}

export function usePresentationStream(dependencies: PresentationStreamDependencies = {}) {
  const history = dependencies.history ?? workbenchApi.presentations
  const inspectorHistory = dependencies.inspectorHistory ?? workbenchApi.inspectorPresentations
  const streamUrl = dependencies.streamUrl ?? workbenchApi.presentationStreamUrl
  const eventSourceFactory = dependencies.eventSourceFactory ?? (url => new EventSource(url))
  const reconnectDelayMs = dependencies.reconnectDelayMs ?? 1200

  const publicPresentations = ref<PublicPresentation[]>([])
  const inspectorPresentations = ref<PublicPresentation[]>([])
  const presentationCursor = ref(-1)
  const connectionState = ref<StreamState>('idle')
  const reconnectCount = ref(0)
  const lastEventAt = ref('')
  const gap = ref(false)
  const syncError = ref('')
  const seenPresentationIds = new Set<string>()

  let activeWorkItemId = ''
  let generation = 0
  let source: EventSourceLike | null = null
  let reconnectTimer = 0

  function merge(items: PublicPresentation[], target: 'public' | 'inspector') {
    const destination = target === 'public' ? publicPresentations : inspectorPresentations
    const byId = new Map(destination.value.map(item => [item.presentationId, item]))
    for (const item of items) {
      if (item.visibility === 'INTERNAL') {
        syncError.value = '服务端返回了不可公开的内部 Presentation'
        continue
      }
      if (target === 'public' && item.visibility !== 'PUBLIC') continue
      byId.set(item.presentationId, item)
      if (item.visibility === 'PUBLIC') seenPresentationIds.add(item.presentationId)
      if (target === 'public') {
        presentationCursor.value = Math.max(presentationCursor.value, item.sequence)
      }
    }
    destination.value = [...byId.values()].sort((left, right) => left.sequence - right.sequence)
  }

  async function loadAll(loader: typeof history, workItemId: string) {
    const result: PublicPresentation[] = []
    let cursor = -1
    for (;;) {
      const page = await loader(workItemId, cursor, 500)
      if (!page.length) break
      result.push(...page)
      cursor = page.at(-1)?.sequence ?? cursor
      if (page.length < 500) break
    }
    return result
  }

  function closeSource() {
    source?.close()
    source = null
    window.clearTimeout(reconnectTimer)
  }

  function parse(raw: Event): PublicPresentation | null {
    try {
      return JSON.parse((raw as MessageEvent<string>).data) as PublicPresentation
    } catch {
      syncError.value = 'Presentation SSE 返回了无法解析的数据'
      return null
    }
  }

  function open(workItemId: string, token: number) {
    if (token !== generation || workItemId !== activeWorkItemId) return
    connectionState.value = 'connecting'
    const next = eventSourceFactory(streamUrl(workItemId, presentationCursor.value))
    source = next
    next.onopen = () => {
      if (token === generation && workItemId === activeWorkItemId) connectionState.value = 'live'
    }
    next.addEventListener('presentation', raw => {
      if (token !== generation || workItemId !== activeWorkItemId) return
      const item = parse(raw)
      if (!item || item.workItemId !== workItemId || seenPresentationIds.has(item.presentationId)) return
      merge([item], 'public')
      merge([item], 'inspector')
      lastEventAt.value = item.occurredAt
    })
    next.addEventListener('gap', () => {
      if (token !== generation) return
      gap.value = true
      void recover(workItemId, token, 'Presentation sequence gap')
    })
    next.addEventListener('sync-error', () => {
      if (token !== generation) return
      void recover(workItemId, token, 'Presentation sync error')
    })
    next.onerror = () => {
      if (token !== generation || workItemId !== activeWorkItemId) return
      void recover(workItemId, token, 'Presentation SSE disconnected')
    }
  }

  async function recover(workItemId: string, token: number, reason: string) {
    if (token !== generation || workItemId !== activeWorkItemId) return
    closeSource()
    connectionState.value = 'recovering'
    syncError.value = reason
    reconnectCount.value += 1
    try {
      const [publicHistory, inspector] = await Promise.all([
        loadAll(history, workItemId), loadAll(inspectorHistory, workItemId),
      ])
      if (token !== generation || workItemId !== activeWorkItemId) return
      publicPresentations.value = []
      inspectorPresentations.value = []
      seenPresentationIds.clear()
      presentationCursor.value = -1
      merge(publicHistory, 'public')
      merge(inspector, 'inspector')
      gap.value = false
      syncError.value = ''
      reconnectTimer = window.setTimeout(() => open(workItemId, token), reconnectDelayMs)
    } catch (cause) {
      if (token !== generation) return
      connectionState.value = 'error'
      syncError.value = cause instanceof Error ? cause.message : reason
    }
  }

  async function start(workItemId: string) {
    stop()
    activeWorkItemId = workItemId
    const token = ++generation
    reconnectCount.value = 0
    connectionState.value = 'connecting'
    try {
      const [publicHistory, inspector] = await Promise.all([
        loadAll(history, workItemId), loadAll(inspectorHistory, workItemId),
      ])
      if (token !== generation || workItemId !== activeWorkItemId) return
      merge(publicHistory, 'public')
      merge(inspector, 'inspector')
      open(workItemId, token)
    } catch (cause) {
      if (token !== generation) return
      connectionState.value = 'error'
      syncError.value = cause instanceof Error ? cause.message : 'Presentation history load failed'
    }
  }

  function stop() {
    generation += 1
    closeSource()
    activeWorkItemId = ''
    publicPresentations.value = []
    inspectorPresentations.value = []
    presentationCursor.value = -1
    connectionState.value = 'idle'
    lastEventAt.value = ''
    gap.value = false
    syncError.value = ''
    seenPresentationIds.clear()
    reconnectCount.value = 0
  }

  return {
    publicPresentations, inspectorPresentations, presentationCursor, connectionState,
    reconnectCount, lastEventAt, gap, syncError, seenPresentationIds, start, stop,
    activeWorkItemId: () => activeWorkItemId,
  }
}
