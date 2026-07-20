import type { ApprovalRecord } from '../types/agent'
import type { ConversationEntry, ConversationItem, ConversationItemStatus, PrimaryAnswerView } from '../types/conversation'
import type { PublicPresentation, WorkInput, WorkItemDetail } from '../types/workbench'

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
    || item.kind === 'RETRY' || item.kind === 'RECOVERY' || item.kind === 'FINAL_RESULT') return 'AGENT_STATUS'
  if (item.kind === 'ROUTE_SUMMARY') return 'ROUTE_SUMMARY'
  if (item.kind === 'STANDARD_PROCESS' || item.kind === 'EXECUTION_PLAN') return 'TASK_PLAN'
  if (item.kind === 'TOOL_ACTIVITY') return 'TOOL_CALL'
  if (item.kind === 'AGENT_DELEGATION') return 'AGENT_DELEGATION'
  if (item.kind === 'APPROVAL_REQUIRED') return 'APPROVAL_REQUEST'
  if (item.kind === 'ERROR') return 'ERROR'
  return null
}

function sameSteps(left: string[], right: string[]) {
  const normalize = (values: string[]) => values.map(value => value.trim().replace(/\s+/g, ' ')).join('\n')
  return normalize(left) === normalize(right)
}

function visiblePresentations(presentations: PublicPresentation[]) {
  const unique = new Map<string, PublicPresentation>()
  presentations.filter(item => item.visibility === 'PUBLIC')
    .forEach(item => unique.set(item.presentationId, item))
  const sorted = [...unique.values()].sort((left, right) => left.sequence - right.sequence)
  const standardProcesses = sorted.filter(item => item.kind === 'STANDARD_PROCESS').map(item => item.steps)
  const withoutDuplicatePlans = sorted.filter(item => item.kind !== 'EXECUTION_PLAN'
    || !standardProcesses.some(steps => sameSteps(steps, item.steps)))
  const latestTool = new Map<string, PublicPresentation>()
  withoutDuplicatePlans.filter(item => item.kind === 'TOOL_ACTIVITY').forEach(item => {
    const key = item.detail.referenceId || item.presentationId
    const current = latestTool.get(key)
    if (!current || item.sequence > current.sequence) {
      const previousArguments = current?.detail.tool?.publicArguments ?? {}
      const currentArguments = item.detail.tool?.publicArguments ?? {}
      const merged = item.detail.tool && !Object.keys(currentArguments).length && Object.keys(previousArguments).length
        ? { ...item, detail: { ...item.detail, tool: { ...item.detail.tool, publicArguments: previousArguments } } }
        : item
      latestTool.set(key, merged)
    }
  })
  const nonTools = withoutDuplicatePlans.filter(item => item.kind !== 'TOOL_ACTIVITY')
  return [...nonTools, ...latestTool.values()].sort((left, right) => left.sequence - right.sequence)
}

function publicItems(presentations: PublicPresentation[], approval: ApprovalRecord | null): ConversationItem[] {
  return visiblePresentations(presentations)
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
  presentations: PublicPresentation[]
  approval: ApprovalRecord | null
  answer: PrimaryAnswerView
}

export function projectConversationItems(source: ConversationProjectionInput): ConversationEntry[] {
  const { detail, inputs, presentations, approval, answer } = source
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

  if (answer.content || answer.state === 'WAITING' || answer.state === 'FINALIZING') items.push({
    id: `answer-${work.workItemId}`,
    type: 'FINAL_ANSWER',
    createdAt: answer.createdAt || work.updatedAt,
    title: '最终回答',
    content: answer.content,
    status: answer.state === 'FAILED' || answer.state === 'CANCELLED' ? 'failed'
      : answer.state === 'COMPLETED' ? 'completed' : 'active',
    live: answer.state === 'STREAMING' || answer.state === 'FINALIZING',
    answerState: answer.state,
  })

  return items
}
