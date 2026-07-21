import type { ConversationTurn, WorkInput, WorkItem } from '../types/workbench'

export function projectConversationTurns(inputs: WorkInput[], workItems: WorkItem[]): ConversationTurn[] {
  const inputById = new Map(inputs.map(input => [input.inputId, input]))
  return workItems
    .filter(work => inputById.has(work.sourceInputId))
    .map(work => {
      const input = inputById.get(work.sourceInputId)!
      return {
        turnId: input.inputId,
        conversationId: work.conversationId,
        inputId: input.inputId,
        workItemId: work.workItemId,
        userMessage: input.content || work.originalGoal,
        executionTarget: work.activeExecutionTarget,
        controlState: work.controlState,
        executionState: work.executionState,
        outcome: work.outcome,
        activeRunId: work.activeRunId,
        activeIncidentId: work.activeIncidentId,
        activePlanId: work.activeRecoveryPlanId,
        createdAt: input.createdAt || work.createdAt,
        completedAt: work.completedAt,
      } satisfies ConversationTurn
    })
    .sort((left, right) => new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()
      || left.turnId.localeCompare(right.turnId))
}

export function isTerminalTurn(turn: ConversationTurn) {
  const state = `${turn.controlState} ${turn.executionState} ${turn.outcome}`.toUpperCase()
  return /(CLOSED|COMPLETED|FAILED|CANCELLED|ABANDONED|RESOLVED|ASSESSED|REJECTED)/.test(state)
}
