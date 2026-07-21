import type { ApprovalRecord } from './agent'
import type { PublicPresentationKind, RoutePreview } from './workbench'

export type PrimaryAnswerState =
  | 'IDLE' | 'WAITING' | 'STREAMING' | 'FINALIZING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export type ConversationItemType =
  | 'USER_MESSAGE'
  | 'AGENT_STATUS'
  | 'TASK_PLAN'
  | 'ROUTE_SUMMARY'
  | 'TOOL_CALL'
  | 'TOOL_RESULT'
  | 'AGENT_DELEGATION'
  | 'INCIDENT_PREVIEW'
  | 'APPROVAL_REQUEST'
  | 'FINAL_ANSWER'
  | 'ERROR'
  | 'EXECUTION_NARRATIVE'

export type ConversationItemStatus = 'pending' | 'active' | 'completed' | 'failed' | 'waiting'

export interface ConversationToolData {
  callId: string
  toolName: string
  displayName: string
  arguments: Record<string, unknown>
  result?: Record<string, unknown>
  summary: string
  actionSummary: string
  resultCount?: number
  durationMs?: number
  attemptLabel: string
}

export interface ConversationAttachment {
  name: string
  size: number
  mediaType: string
}

export interface ConversationItem {
  id: string
  type: ConversationItemType
  createdAt: string
  title: string
  content: string
  status: ConversationItemStatus
  steps?: string[]
  attachments?: ConversationAttachment[]
  tool?: ConversationToolData
  preview?: RoutePreview
  approval?: ApprovalRecord
  live?: boolean
  answerState?: PrimaryAnswerState
  presentationKind?: PublicPresentationKind
  error?: {
    code: string
    retryable: boolean
    correlationId: string
    traceId: string
  }
  narrative?: ExecutionNarrativeGroup
}

export interface ExecutionNarrativeItem {
  id: string
  status: ConversationItemStatus
  summary: string
  detail: string
  sourcePresentationIds: string[]
  occurredAt: string
  metadata: Array<{ label: string; value: string; code?: boolean }>
  findings: string[]
}

export interface ExecutionNarrativeGroup {
  groupId: string
  turnId: string
  title: string
  status: ConversationItemStatus
  summary: string
  items: ExecutionNarrativeItem[]
  sourcePresentationIds: string[]
  startedAt: string
  completedAt: string
  expandable: boolean
}

export interface PresentationLocator {
  turnId: string
  presentationIds: string[]
}

export interface ConversationTurnView {
  turn: import('./workbench').ConversationTurn
  entries: ConversationItem[]
  stepCount: number
  agentCount: number
  durationMs: number
}

export interface PrimaryAnswerView {
  state: PrimaryAnswerState
  content: string
  persistedMessageId: string
  createdAt: string
}

export type ConversationEntry = ConversationItem
