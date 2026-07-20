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
export interface ExecutionNodeMetrics {
  modelCalls: number
  toolCalls: number
  promptTokens: number
  completionTokens: number
  estimatedCost: number
  durationMs: number
}
export interface ExecutionTreeMetrics extends Omit<ExecutionNodeMetrics, 'durationMs'> {
  agentNodes: number
  evidenceCount: number
  conflictCount: number
  syntheticCoordinatorModelCalls: number
}
export interface ExecutionCoordinatorNode {
  nodeId: string
  label: string
  status: string
  synthetic: boolean
  modelCalls: number
  span: RuntimeTraceSpan
}
export interface ExecutionAgentNode {
  nodeId: string
  role: string
  taskId: string
  runId: string
  attempt: number
  maxAttempts: number
  status: string
  objective: string
  error: string
  trace?: RuntimeRunTrace
  evidence: IncidentEvidence[]
  metrics: ExecutionNodeMetrics
}
export interface ExecutionConflict {
  conflictId: string
  conflictType: string
  severity: string
  metricKey: string
  evidenceIds: string[]
  status: string
  details: Record<string, unknown>
}
export interface WorkExecutionTree {
  workItemId: string
  executionTarget: string
  treeType: string
  executionId: string
  coordinator?: ExecutionCoordinatorNode
  agents: ExecutionAgentNode[]
  evidence: IncidentEvidence[]
  conflicts: ExecutionConflict[]
  assessment: Record<string, unknown>
  recoveryPlans: IncidentRecoveryPlan[]
  metrics: ExecutionTreeMetrics
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
import type { IncidentEvidence, IncidentRecoveryPlan, RuntimeRunTrace, RuntimeTraceSpan } from './incident'
