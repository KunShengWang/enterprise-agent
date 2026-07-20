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

export interface ConversationItem {
  id: string
  type: ConversationItemType
  createdAt: string
  title: string
  content: string
  status: ConversationItemStatus
  steps?: string[]
  tool?: ConversationToolData
  preview?: RoutePreview
  approval?: ApprovalRecord
  live?: boolean
  answerState?: PrimaryAnswerState
  presentationKind?: PublicPresentationKind
}

export interface PrimaryAnswerView {
  state: PrimaryAnswerState
  content: string
  persistedMessageId: string
  createdAt: string
}

export type ConversationEntry = ConversationItem
