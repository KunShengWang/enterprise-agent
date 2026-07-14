import { apiRequest, jsonBody } from './http'
import type {
  AgentEvent,
  AgentRequest,
  AgentResponse,
  AgentRunRecord,
  ApprovalDecision,
  ApprovalRecord,
  SkillDefinition,
  ToolDefinition,
} from '../types/agent'

export const agentApi = {
  health: () => apiRequest<{ name: string; stage: string; mockMode: boolean }>('/api/agent/health'),

  recentRuns: (limit = 30) => apiRequest<AgentRunRecord[]>(`/api/agent/runs?limit=${limit}`),
  run: (request: AgentRequest) => apiRequest<AgentResponse>('/api/agent/runs', {
    method: 'POST',
    ...jsonBody(request),
  }),
  findRun: (runId: string) => apiRequest<AgentRunRecord>(`/api/agent/runs/${encodeURIComponent(runId)}`),
  runEvents: (runId: string, afterSequence = -1, limit = 500) =>
    apiRequest<AgentEvent[]>(
      `/api/agent/runs/${encodeURIComponent(runId)}/events?afterSequence=${afterSequence}&limit=${limit}`,
    ),
  resumeRun: (runId: string) => apiRequest<AgentResponse>(`/api/agent/runs/${encodeURIComponent(runId)}/resume`, {
    method: 'POST',
  }),
  cancelRun: (runId: string) => apiRequest<{ runId: string; cancellationRequested: boolean }>(
    `/api/agent/runs/${encodeURIComponent(runId)}/cancel`,
    { method: 'POST' },
  ),

  approvals: (limit = 50) => apiRequest<ApprovalRecord[]>(`/api/agent/guardrails/approvals?limit=${limit}`),
  approval: (approvalId: string) => apiRequest<ApprovalRecord>(
    `/api/agent/guardrails/approvals/${encodeURIComponent(approvalId)}`,
  ),
  decideApproval: (approvalId: string, approved: boolean, reviewer: string, reason: string) =>
    apiRequest<ApprovalDecision>(`/api/agent/guardrails/approvals/${encodeURIComponent(approvalId)}/decide`, {
      method: 'POST',
      ...jsonBody({ approved, reviewer, reason }),
    }),

  tools: () => apiRequest<ToolDefinition[]>('/api/agent/tools'),
  toolRuns: (limit = 30) => apiRequest<Array<Record<string, unknown>>>(`/api/agent/tools/runs?limit=${limit}`),
  toolStats: () => apiRequest<Record<string, unknown>>('/api/agent/tools/runs/stats'),
  toolExecutions: (runId: string) => apiRequest<Array<Record<string, unknown>>>(
    `/api/agent/tools/executions?runId=${encodeURIComponent(runId)}`,
  ),
  skills: () => apiRequest<SkillDefinition[]>('/api/agent/skills'),
}
