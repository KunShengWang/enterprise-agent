import type { ApprovalRecord } from '../types/agent'
import type { AgentConversationMessage } from '../types/agent'
import type { ConversationEntry, ConversationItem, ConversationItemStatus, PrimaryAnswerView } from '../types/conversation'
import type { ConversationTurn, PublicPresentation, WorkExecutionTree, WorkInput, WorkItemDetail } from '../types/workbench'
import type { WorkItem } from '../types/workbench'
import { isToolCallProtocolEnvelope, normalizeAssistantContent } from './publicContent'
import { aggregateExecutionNarrative } from './executionNarrative'

function userInputPresentation(content: string) {
  const marker = '<workbench_attachments>'
  const markerIndex = content.indexOf(marker)
  if (markerIndex < 0) return { content, attachments: [] }
  const attachmentBlock = content.slice(markerIndex + marker.length)
  const attachments = [...attachmentBlock.matchAll(/<attachment name="([^"]+)" media-type="([^"]*)" size="(\d+)">/g)]
    .map(match => ({ name: match[1], mediaType: match[2], size: Number(match[3]) }))
  return { content: content.slice(0, markerIndex).trim(), attachments }
}

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
      if (type === 'APPROVAL_REQUEST' && !approval) return []
      return [{
        id: `presentation-${item.presentationId}`,
        type,
        createdAt: item.occurredAt,
        title: item.title,
        content: item.summary,
        steps: item.steps,
        status: presentationStatus(item.status),
        presentationKind: item.kind,
        approval: item.kind === 'APPROVAL_REQUIRED' ? approval ?? undefined : undefined,
        tool: item.detail.tool ? {
          callId: item.detail.referenceId,
          toolName: item.detail.tool.toolName,
          displayName: item.detail.tool.displayName,
          arguments: item.detail.tool.publicArguments,
          summary: item.detail.tool.resultSummary || item.detail.tool.actionSummary,
          actionSummary: item.detail.tool.actionSummary,
          resultCount: item.detail.tool.resultCount,
          durationMs: item.detail.tool.durationMs,
          attemptLabel: item.detail.tool.attemptLabel,
        } : undefined,
        error: item.kind === 'ERROR' ? {
          code: String(item.detail.attributes.safeErrorCode ?? 'RUN_FAILED'),
          retryable: item.detail.attributes.retryable === 'true',
          correlationId: String(item.detail.attributes.correlationId ?? ''),
          traceId: String(item.detail.attributes.traceId ?? ''),
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
  workItems?: WorkItem[]
  messages?: AgentConversationMessage[]
}

function historicalConversationItems(source: ConversationProjectionInput): ConversationItem[] {
  const current = source.detail.workItem
  const inputs = new Map(source.inputs.map(input => [input.inputId, input]))
  return (source.workItems ?? [])
    .filter(work => work.conversationId === current.conversationId && work.workItemId !== current.workItemId)
    .filter(work => new Date(work.createdAt).getTime() <= new Date(current.createdAt).getTime())
    .sort((left, right) => new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime())
    .flatMap(work => {
      const displayedInput = userInputPresentation(inputs.get(work.sourceInputId)?.content || work.originalGoal)
      const result: ConversationItem[] = [{
        id: `history-user-${work.workItemId}`,
        type: 'USER_MESSAGE', createdAt: work.createdAt, title: '你的目标',
        content: displayedInput.content, attachments: displayedInput.attachments, status: 'completed',
      }]
      const message = (source.messages ?? [])
        .filter(item => item.role === 'ASSISTANT' && item.runId === work.activeRunId)
        .sort((left, right) => right.sequence - left.sequence)[0]
      if (message) {
        const content = normalizeAssistantContent(message.content)
        if (content && !isToolCallProtocolEnvelope(content)) result.push({
          id: `history-answer-${work.workItemId}-${message.messageId}`,
          type: 'FINAL_ANSWER', createdAt: message.createdAt, title: '最终回答',
          content, status: 'completed', answerState: 'COMPLETED',
        })
      }
      return result
    })
}

export function projectConversationItems(source: ConversationProjectionInput): ConversationEntry[] {
  const { detail, inputs, presentations, approval, answer } = source
  const work = detail.workItem
  const items: ConversationItem[] = historicalConversationItems(source)
  const userInput = inputs.find(input => input.inputId === work.sourceInputId)
  const displayedInput = userInputPresentation(userInput?.content || work.originalGoal)
  items.push({
    id: `user-${work.sourceInputId || work.workItemId}`,
    type: 'USER_MESSAGE',
    createdAt: userInput?.createdAt || work.createdAt,
    title: '你的目标',
    content: displayedInput.content,
    attachments: displayedInput.attachments,
    status: 'completed',
  })

  items.push(...publicItems(presentations, approval))

  if (detail.preview) items.push({
    id: `preview-${detail.preview.previewId}`,
    type: 'INCIDENT_PREVIEW',
    createdAt: work.updatedAt,
    title: detail.preview.targetId === 'INCIDENT_INVESTIGATION'
      ? '启动只读 Multi-Agent 事故调查' : '需要确认执行范围',
    content: detail.preview.targetId === 'INCIDENT_INVESTIGATION'
      ? '确认后将调度订单、库存和消息链路三个只读 Specialist 收集证据；消息链路 Specialist 同时核对持久化死信与队列运行态，再由 Reviewer 检查冲突并生成 Assessment。不会执行恢复。'
      : '开始执行前，请核对目标、范围和风险边界。',
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

  if (answer.content || ['WAITING', 'STREAMING', 'FINALIZING'].includes(answer.state)) items.push({
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

export interface TurnConversationProjectionInput extends Omit<ConversationProjectionInput, 'workItems'> {
  turn: ConversationTurn
  tree?: WorkExecutionTree | null
}

export function projectTurnConversationItems(source: TurnConversationProjectionInput): ConversationItem[] {
  const { detail, inputs, presentations, approval, answer, turn } = source
  const work = detail.workItem
  const input = inputs.find(item => item.inputId === work.sourceInputId)
  const displayedInput = userInputPresentation(input?.content || turn.userMessage || work.originalGoal)
  const items: ConversationItem[] = [{
    id: `turn-${turn.turnId}-user`,
    type: 'USER_MESSAGE',
    createdAt: input?.createdAt || turn.createdAt,
    title: '你的目标',
    content: displayedInput.content,
    attachments: displayedInput.attachments,
    status: 'completed',
  }]

  for (const narrative of aggregateExecutionNarrative(turn, presentations, source.tree)) {
    items.push({
      id: narrative.groupId,
      type: 'EXECUTION_NARRATIVE',
      createdAt: narrative.startedAt,
      title: narrative.title,
      content: narrative.summary,
      status: narrative.status,
      narrative,
    })
  }

  items.push(...publicItems(presentations, approval).filter(item =>
    item.type === 'TOOL_CALL' || item.type === 'APPROVAL_REQUEST' || item.type === 'ERROR'
      || item.presentationKind === 'WAITING_FOR_USER'))

  if (detail.preview) items.push({
    id: `preview-${detail.preview.previewId}`,
    type: 'INCIDENT_PREVIEW',
    createdAt: work.updatedAt,
    title: detail.preview.targetId === 'INCIDENT_INVESTIGATION'
      ? '启动只读 Multi-Agent 事故调查' : '需要确认执行范围',
    content: detail.preview.targetId === 'INCIDENT_INVESTIGATION'
      ? '确认后将调度订单、库存和消息链路三个只读 Specialist 收集证据；消息链路 Specialist 同时核对持久化死信与队列运行态，再由 Reviewer 检查冲突并生成 Assessment。不会执行恢复。'
      : '开始执行前，请核对目标、范围和风险边界。',
    status: detail.preview.status === 'ACTIVE' ? 'waiting' : 'completed',
    preview: detail.preview,
  })

  const hasApproval = presentations.some(item => item.visibility === 'PUBLIC'
    && item.kind === 'APPROVAL_REQUIRED')
  if (approval && !hasApproval) items.push({
    id: `approval-${approval.approvalId}`,
    type: 'APPROVAL_REQUEST', createdAt: approval.createdAt,
    title: '高风险操作等待审批', content: '继续执行前需要人工确认。',
    status: 'waiting', approval,
  })

  if (answer.content || ['WAITING', 'STREAMING', 'FINALIZING'].includes(answer.state)) items.push({
    id: `turn-${turn.turnId}-answer`,
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
