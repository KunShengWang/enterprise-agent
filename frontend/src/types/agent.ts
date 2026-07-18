export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
}

export interface AgentRequest {
  conversationId: string
  userId: string
  question: string
  metadata: Record<string, unknown>
  scenarioId?: string
}

export interface AgentConversationMessage {
  messageId: string
  runId: string
  sequence: number
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
}

export interface OrderCareCaseSnapshot {
  schemaVersion: string
  caseKey: string
  canonicalRequestId: string
  found: boolean
  diagnosisCode: string
  factsComplete: boolean
  recoveryEligible: boolean
  reservation?: {
    orderNo?: string
    orderStatusName?: string
    latestOrderEventType?: string
  }
  order?: {
    dependencyAvailable?: boolean
    exists?: boolean
    orderNo?: string
    statusName?: string
    queryError?: string
  }
  deduct?: {
    deductNo?: string
    statusName?: string
    quantity?: number
  }
  inventory?: {
    totalStock?: number
    availableStock?: number
    lockedStock?: number
    soldStock?: number
    invariantOk?: boolean
  }
  deadLetters: Array<{
    deadLetterId: number
    messageType: string
    statusName: string
    replayCount: number
  }>
  candidates: Array<{
    candidateId: string
    actionType: string
    eligible: boolean
    blockedBy: string
  }>
  evidence: string[]
  hardRisks: string[]
}

export interface OrderCareRecoveryProposalSnapshot {
  schemaVersion: string
  proposalId: string
  proposalVersion: number
  proposalStatus: string
  actionRequestId: string
  actionStatus: string
  caseOutcome: string
  caseKey: string
  targetType: string
  targetKey: string
  stateFingerprint: string
  effectsDigest: string
  warningsDigest: string
  previewDigest: string
  canExecute: boolean
  effects: string[]
  warnings: string[]
  suggestedReason: string
  expiresAt: string
  approvedBy?: string
  approvalComment?: string
}

export interface OrderCareConvergenceSnapshot {
  status: string
  attempts: number
  actionStatus: string
  caseOutcome: string
  deductReleased: boolean
  inventoryInvariantOk: boolean
  relatedDeadLettersTerminal: boolean
  message?: string
}

export interface OrderCareRecoveryExecutionSnapshot {
  execution: OrderCareRecoveryProposalSnapshot
  convergence: OrderCareConvergenceSnapshot
}

export interface OrderCareRecoveryActionSnapshot {
  schemaVersion: string
  proposalId: string
  actionRequestId: string
  actionStatus: string
  caseOutcome: string
  reconciliationStatus: string
  executionOwner?: string
  executionLeaseUntil?: string
  leaseExpired?: boolean
  reconcileCount?: number
  lastError?: string
}

export interface OrderCareRecoveryReconciliationSnapshot {
  status: string
  attempts: number
  responseLost: boolean
  executeReissuedWithSameId: boolean
  action?: OrderCareRecoveryActionSnapshot
  convergence?: OrderCareConvergenceSnapshot
}

export interface AgentStreamEvent {
  eventId: string
  traceId: string
  conversationId: string
  sequence: number
  type: string
  content: string
  createdAt: string
  metadata: Record<string, unknown>
}

export interface AgentRunBudgetSnapshot {
  turns: number
  modelCalls: number
  toolCalls: number
  inputTokens: number
  outputTokens: number
  estimatedCost: number
  startedAt: string
  deadline: string
  cancelled: boolean
  remainingExecutionMillis: number
  executionPaused: boolean
}

export interface ToolCallRequest {
  toolName: string
  requestId: string
  arguments: Record<string, unknown>
}

export interface ToolCallResult {
  toolName: string
  success: boolean
  content: string
  errorMessage: string
  metadata: Record<string, unknown>
}

export interface AgentExecutionProfile {
  name: string
  systemPrompt: string
  allowedCapabilities: string[]
  limits: Record<string, unknown>
  longTermMemoryEnabled: boolean
}

export interface AgentRunRecord {
  runId: string
  traceId: string
  conversationId: string
  userId: string
  request: AgentRequest
  executionProfile: AgentExecutionProfile | null
  budgetSnapshot: AgentRunBudgetSnapshot | null
  state: string
  phase: string
  approvalId: string
  pendingToolCall: ToolCallRequest | null
  toolResults: ToolCallResult[]
  usedTools: string[]
  usedRag: boolean
  blockedByGuardrail: boolean
  answer: string
  failureReason: string
  resumeCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface AgentEvent {
  eventId: string
  runId: string
  sessionId: string
  sequence: number
  type: string
  content: string
  payload: Record<string, unknown>
  createdAt: string
}

export interface AgentResponse {
  runId: string
  conversationId: string
  status: string
  answer: string
  approvalId: string
  steps: Array<Record<string, unknown>>
  trace: Record<string, unknown>
}

export interface ApprovalRecord {
  approvalId: string
  runId: string
  conversationId: string
  toolCallRequest: ToolCallRequest
  reason: string
  status: string
  reviewer: string
  decisionReason: string
  createdAt: string
  expiresAt: string
  decidedAt: string | null
}

export interface ApprovalDecision {
  approvalId: string
  status: string
  reviewer: string
  reason: string
  decidedAt: string
  approved: boolean
  pending: boolean
}

export interface ToolDefinition {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  riskLevel: string
  source?: string
  [key: string]: unknown
}

export interface SkillDefinition {
  name: string
  description: string
  [key: string]: unknown
}

export interface RetrievedDocument {
  documentId: string
  title: string
  content: string
  score: number
  metadata: Record<string, unknown>
}

export interface RagResult {
  query: string
  documents: RetrievedDocument[]
  enoughEvidence: boolean
  requestedTopK: number
  effectiveTopK: number
  minSimilarity: number
  durationMs: number
  retrievalMode: string
}

export interface MemorySearchResult {
  type: string
  id: string
  content: string
  score: number
  metadata: Record<string, unknown>
}

export interface UserProfileItem {
  key: string
  value: string
  source: string
  updatedAt: string
  [key: string]: unknown
}

export interface UserProfile {
  userId: string
  items: UserProfileItem[]
  updatedAt: string | null
}

export interface TraceRun {
  traceId: string
  conversationId: string
  question: string
  status: string
  startedAt: string
  endedAt: string
  durationMs: number
  failureReason: string
  estimatedPromptTokens: number
  estimatedCompletionTokens: number
  estimatedCost: number
  spans: Array<Record<string, unknown>>
  events: Array<Record<string, unknown>>
  replayEvents: Array<Record<string, unknown>>
  metrics: Record<string, unknown>
}
