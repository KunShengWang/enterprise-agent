import type { InspectorScope, TurnExecutionSnapshot, WorkEvent } from '../types/workbench'

export function activeTurnSourceIds(snapshot: TurnExecutionSnapshot) {
  return new Set([snapshot.detail.workItem.workItemId, snapshot.detail.workItem.activeRunId,
    snapshot.detail.workItem.activeIncidentId, snapshot.detail.workItem.activeRecoveryPlanId].filter(Boolean))
}

export function eventsForInspectorScope(scope: InspectorScope, snapshot: TurnExecutionSnapshot): WorkEvent[] {
  if (scope !== 'TURN') return snapshot.events
  const sourceIds = activeTurnSourceIds(snapshot)
  return snapshot.events.filter(event => !event.sourceId || sourceIds.has(event.sourceId)
    || event.sourceType === 'WORK_ITEM')
}

export function snapshotsForInspectorScope(scope: InspectorScope,
                                           snapshots: TurnExecutionSnapshot[],
                                           selectedTurnId: string,
                                           current: TurnExecutionSnapshot | null) {
  if (scope === 'CONVERSATION') return snapshots.map(item =>
    item.turn.turnId === selectedTurnId && current ? current : item)
  return current ? [current] : []
}
