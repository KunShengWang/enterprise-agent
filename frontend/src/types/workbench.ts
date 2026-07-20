export interface WorkInput {
  inputId: string
  clientInputId: string
  conversationId: string
  content: string
  inputKind: string
  commandType?: string
  classificationStatus: string
  createdAt: string
}

export interface WorkItem {
  workItemId: string
  conversationId: string
  originalGoal: string
  controlState: string
  executionState: string
  outcome: string
  activeExecutionTarget: string
  activeRunId: string
  activeIncidentId: string
  activeRecoveryPlanId: string
  routingFailureCode: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface WorkFocus { focusedWorkItemId: string; version: number }
export interface WorkEvent {
  eventId: string
  sequence: number
  eventType: string
  phase?: string
  summary: string
  projectedAt: string
}
export interface WorkStreamItem {
  kind: 'WORK_EVENT' | 'MODEL_DELTA' | 'GAP' | 'HEARTBEAT' | 'SYNC_ERROR'
  eventId: string
  workSequence: number
  sourceType: string
  sourceId: string
  sourceSequence?: number
  eventType: string
  content: string
  payload: Record<string, unknown>
  createdAt: string
  resumeToken: string
}
export interface WorkLink { linkType: string; linkedId: string; relation: string }
export interface RoutePreview {
  previewId: string
  previewVersion: number
  targetId: string
  validatedInputDigest: string
  scopeDigest: string
  payload: Record<string, unknown>
  status: string
  expiresAt: string
}
export interface RoutingDecision { decisionId: string; decision: Record<string, unknown>; validation: Record<string, unknown>; failureCode: string; failureReason: string }
export interface WorkItemDetail { workItem: WorkItem; focus?: WorkFocus; routingDecision?: RoutingDecision; preview?: RoutePreview; links: WorkLink[]; events: WorkEvent[] }
export interface UnifiedInputResponse { inputId: string; workItemId: string; controlState: string; commandType: string; commandOnly: boolean }
