import type { AgentConversationMessage, ApprovalRecord } from '../types/agent'
import type { ConversationItem, ConversationItemStatus } from '../types/conversation'
import type { PublicPresentation, WorkExecutionTree, WorkInput, WorkItemDetail } from '../types/workbench'

function presentationStatus(status: PublicPresentation['status']): ConversationItemStatus {
  if (status === 'FAILED') return 'failed'
  if (status === 'WAITING') return 'waiting'
  if (status === 'COMPLETED') return 'completed'
  if (status === 'PENDING') return 'pending'
  return 'active'
}

function presentationType(item: PublicPresentation): ConversationItem['type'] | null {
  if (item.kind === 'TASK_UNDERSTANDING' || item.kind === 'ACTION_STARTED'
    || item.kind === 'ACTION_COMPLETED' || item.kind === 'WAITING_FOR_USER'
    || item.kind === 'RETRY' || item.kind === 'RECOVERY') return 'AGENT_STATUS'
  if (item.kind === 'ROUTE_SUMMARY') return 'ROUTE_SUMMARY'
  if (item.kind === 'STANDARD_PROCESS' || item.kind === 'EXECUTION_PLAN') return 'TASK_PLAN'
  if (item.kind === 'TOOL_ACTIVITY') return 'TOOL_CALL'
  if (item.kind === 'AGENT_DELEGATION') return 'AGENT_DELEGATION'
  if (item.kind === 'APPROVAL_REQUIRED') return 'APPROVAL_REQUEST'
  if (item.kind === 'ERROR') return 'ERROR'
  return null
}

function publicItems(presentations: PublicPresentation[], approval: ApprovalRecord | null): ConversationItem[] {
  return [...presentations]
    .filter(item => item.visibility === 'PUBLIC')
    .sort((left, right) => left.sequence - right.sequence)
    .flatMap((item): ConversationItem[] => {
      const type = presentationType(item)
      if (!type) return []
      return [{
        id: `presentation-${item.presentationId}`,
        type,
        createdAt: item.occurredAt,
        title: item.title,
        content: item.summary,
        steps: item.steps,
        status: presentationStatus(item.status),
        approval: item.kind === 'APPROVAL_REQUIRED' ? approval ?? undefined : undefined,
        tool: item.detail.tool ? {
          callId: item.detail.referenceId,
          toolName: item.detail.tool.toolName,
          displayName: item.detail.tool.displayName,
          arguments: item.detail.tool.publicArguments,
          summary: item.detail.tool.resultSummary || item.detail.tool.actionSummary,
          durationMs: item.detail.tool.durationMs,
        } : undefined,
      }]
    })
}

export interface ConversationProjectionInput {
  detail: WorkItemDetail
  inputs: WorkInput[]
  messages: AgentConversationMessage[]
  presentations: PublicPresentation[]
  tree: WorkExecutionTree | null
  approval: ApprovalRecord | null
  liveAnswer: string
}

export function projectConversationItems(source: ConversationProjectionInput): ConversationItem[] {
  const { detail, inputs, messages, presentations, approval, liveAnswer } = source
  const work = detail.workItem
  const items: ConversationItem[] = []
  const userInput = inputs.find(input => input.inputId === work.sourceInputId)
  items.push({
    id: `user-${work.sourceInputId || work.workItemId}`,
    type: 'USER_MESSAGE',
    createdAt: userInput?.createdAt || work.createdAt,
    title: '你的目标',
    content: userInput?.content || work.originalGoal,
    status: 'completed',
  })

  items.push(...publicItems(presentations, approval))

  if (detail.preview) items.push({
    id: `preview-${detail.preview.previewId}`,
    type: 'INCIDENT_PREVIEW',
    createdAt: work.updatedAt,
    title: '需要确认调查范围',
    content: '启动事故调查前，请核对目标、范围和风险边界。',
    status: detail.preview.status === 'ACTIVE' ? 'waiting' : 'completed',
    preview: detail.preview,
  })

  const hasApprovalPresentation = presentations.some(item =>
    item.visibility === 'PUBLIC' && item.kind === 'APPROVAL_REQUIRED')
  if (approval && !hasApprovalPresentation) items.push({
    id: `approval-${approval.approvalId}`,
    type: 'APPROVAL_REQUEST',
    createdAt: approval.createdAt,
    title: '高风险操作等待审批',
    content: '继续执行前需要人工确认。',
    status: 'waiting',
    approval,
  })

  const persisted = messages.find(message => message.role === 'ASSISTANT' && message.runId === work.activeRunId)
  const finalContent = persisted?.content || liveAnswer
  if (finalContent) items.push({
    id: persisted ? `answer-${persisted.messageId}` : `answer-live-${work.workItemId}`,
    type: 'FINAL_ANSWER',
    createdAt: persisted?.createdAt || new Date().toISOString(),
    title: '最终回答',
    content: finalContent,
    status: persisted ? 'completed' : 'active',
    live: !persisted,
  })

  return items
}
