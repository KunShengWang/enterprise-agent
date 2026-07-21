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

export interface ComposerAttachment {
  id: string
  name: string
  size: number
  mediaType: string
  content: string
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
  sourceInputId: string
  routeDecisionId?: string
  routingFailureCode: string
  dispatchRequestId?: string
  version: number
  createdAt: string
  updatedAt: string
  completedAt?: string | null
}

export interface ConversationHistoryItem {
  conversationId: string
  title: string
  latestWorkItem: WorkItem
  workItemCount: number
  updatedAt: string
}

export interface ConversationTurn {
  turnId: string
  conversationId: string
  inputId: string
  workItemId: string
  userMessage: string
  executionTarget: string
  controlState: string
  executionState: string
  outcome: string
  activeRunId: string
  activeIncidentId: string
  activePlanId: string
  createdAt: string
  completedAt?: string | null
}

export type InspectorScope = 'TURN' | 'WORK_ITEM' | 'CONVERSATION'

export interface TurnExecutionSnapshot {
  turn: ConversationTurn
  detail: WorkItemDetail
  publicPresentations: PublicPresentation[]
  inspectorPresentations: PublicPresentation[]
  events: WorkEvent[]
  tree: WorkExecutionTree | null
  budget: WorkItemBudget | null
  approval: import('./agent').ApprovalRecord | null
  answer: import('./conversation').PrimaryAnswerView
}

export interface WorkFocus { focusedWorkItemId: string; version: number }
export interface WorkEvent {
  eventId: string
  workItemId?: string
  sequence: number
  eventType: string
  phase?: string
  summary: string
  projectedAt: string
  sourceType?: string
  sourceId?: string
  sourceEventId?: string
  sourceSequence?: number
  sourceCreatedAt?: string
  correlationId?: string
  causationId?: string
  payload?: Record<string, unknown>
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
export type PublicPresentationKind =
  | 'TASK_UNDERSTANDING' | 'ROUTE_SUMMARY' | 'STANDARD_PROCESS' | 'EXECUTION_PLAN'
  | 'ACTION_STARTED' | 'ACTION_COMPLETED' | 'TOOL_ACTIVITY' | 'AGENT_DELEGATION'
  | 'WAITING_FOR_USER' | 'CONFIRMATION_REQUIRED' | 'APPROVAL_REQUIRED'
  | 'RETRY' | 'RECOVERY' | 'FINAL_RESULT' | 'ERROR'
export type PublicPresentationStatus = 'PENDING' | 'ACTIVE' | 'COMPLETED' | 'FAILED' | 'WAITING'
export interface PublicToolPresentation {
  toolName: string
  displayName: string
  actionSummary: string
  publicArguments: Record<string, unknown>
  resultSummary: string
  resultCount?: number
  durationMs?: number
  attemptLabel: string
}
export interface PublicPresentation {
  presentationId: string
  workItemId: string
  sequence: number
  schemaVersion: number
  kind: PublicPresentationKind
  status: PublicPresentationStatus
  title: string
  summary: string
  steps: string[]
  detail: {
    targetLabel: string
    referenceType: string
    referenceId: string
    tool?: PublicToolPresentation
    attributes: Record<string, string>
  }
  sourceType: string
  sourceId: string
  sourceEventId: string
  occurredAt: string
  visibility: 'PUBLIC' | 'INSPECTOR_ONLY' | 'INTERNAL'
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
  runtimeStatus: string
  objective: string
  error: string
  runtimeWarning: string
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
export interface WorkCommandResult {
  success: boolean
  code: string
  message: string
  commandRequestId: string
  inputId: string
  command: string
  executionTarget: string
  workItemId: string
  underlyingExecutionChanged: boolean
  underlyingRunId: string
  executionStatus: string
  workItem?: WorkItem
}
export type UnifiedSubmitResult = UnifiedInputResponse | WorkCommandResult
export interface BudgetAmount {
  modelCalls: number
  tokens: number
  toolCalls: number
  durationMillis: number
  estimatedCost: number
}
export interface WorkItemBudget {
  accountId: string
  ownerType: string
  ownerId: string
  parentAccountId: string
  status: string
  maximum: BudgetAmount
  reserved: BudgetAmount
  consumed: BudgetAmount
  version: number
  updatedAt: string
}
import type { IncidentEvidence, IncidentRecoveryPlan, RuntimeRunTrace, RuntimeTraceSpan } from './incident'
