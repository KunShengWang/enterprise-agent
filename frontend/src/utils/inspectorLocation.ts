import type { PublicPresentation, WorkEvent } from '../types/workbench'

export interface InspectorLocation {
  presentations: PublicPresentation[]
  event?: WorkEvent
}

export function resolveInspectorLocation(ids: string[],
                                         publicPresentations: PublicPresentation[],
                                         inspectorPresentations: PublicPresentation[],
                                         events: WorkEvent[]): InspectorLocation {
  const requestedSources = [
    ...publicPresentations.filter(item => ids.includes(item.presentationId)),
    ...inspectorPresentations.filter(item => ids.includes(item.presentationId)),
  ]
  const sourceEventIds = new Set(requestedSources.map(item => item.sourceEventId).filter(Boolean))
  const sourceKeys = new Set(requestedSources.map(item => `${item.sourceType}:${item.sourceId}`))
  const presentations = inspectorPresentations.filter(item => ids.includes(item.presentationId)
    || sourceEventIds.has(item.sourceEventId)
    || sourceKeys.has(`${item.sourceType}:${item.sourceId}`))
  presentations.forEach(item => {
    if (item.sourceEventId) sourceEventIds.add(item.sourceEventId)
    sourceKeys.add(`${item.sourceType}:${item.sourceId}`)
  })
  const exactEvent = events.find(item => sourceEventIds.has(item.sourceEventId ?? '')
    || sourceEventIds.has(item.eventId))
  const event = exactEvent ?? events.find(item => sourceKeys.has(`${item.sourceType}:${item.sourceId}`))
  return { presentations, event }
}
