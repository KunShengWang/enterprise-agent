import { apiRequest, jsonBody } from './http'
import type {
  AgentEvent,
  AgentConversationMessage,
  AgentRequest,
  AgentResponse,
  AgentRunRecord,
  ApprovalDecision,
  ApprovalRecord,
  MemorySearchResult,
  RagResult,
  SkillDefinition,
  TraceRun,
  ToolDefinition,
  UserProfile,
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
  conversationMessages: (conversationId: string, limit = 200) =>
    apiRequest<AgentConversationMessage[]>(
      `/api/agent/conversations/${encodeURIComponent(conversationId)}/messages?limit=${limit}`,
    ),
  resumeRun: (runId: string) => apiRequest<AgentResponse>(`/api/agent/runs/${encodeURIComponent(runId)}/resume`, {
    method: 'POST',
  }),
  pauseRun: (runId: string) => apiRequest<{ runId: string; pauseRequested: boolean }>(
    `/api/agent/runs/${encodeURIComponent(runId)}/pause`,
    { method: 'POST' },
  ),
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

  ragSearch: (query: string, topK = 3) => apiRequest<RagResult>('/api/agent/rag/search', {
    method: 'POST',
    ...jsonBody({ query, topK }),
  }),
  ragStats: () => apiRequest<Record<string, unknown>>('/api/agent/rag/stats'),
  ragRuns: (limit = 20) => apiRequest<Array<Record<string, unknown>>>(`/api/agent/rag/runs?limit=${limit}`),
  ragRunStats: (limit = 100) => apiRequest<Record<string, unknown>>(`/api/agent/rag/runs/stats?limit=${limit}`),
  ragCacheStats: () => apiRequest<Record<string, unknown>>('/api/agent/rag/cache/stats'),

  recallMemory: (conversationId: string, userId: string, query: string, limit = 8) => {
    const search = new URLSearchParams({ query, limit: String(limit) })
    if (userId.trim()) search.set('userId', userId.trim())
    return apiRequest<MemorySearchResult[]>(
      `/api/agent/memory/conversations/${encodeURIComponent(conversationId)}/recall?${search}`,
    )
  },
  userProfile: (userId: string) => apiRequest<UserProfile>(
    `/api/agent/memory/users/${encodeURIComponent(userId)}/profile`,
  ),
  upsertUserProfile: (userId: string, key: string, value: string, source: string) =>
    apiRequest<UserProfile>(`/api/agent/memory/users/${encodeURIComponent(userId)}/profile`, {
      method: 'POST',
      ...jsonBody({ key, value, source }),
    }),

  traces: (limit = 20) => apiRequest<TraceRun[]>(`/api/agent/traces?limit=${limit}`),
  trace: (traceId: string) => apiRequest<TraceRun>(`/api/agent/traces/${encodeURIComponent(traceId)}`),
  traceReplay: (traceId: string) => apiRequest<Array<Record<string, unknown>>>(
    `/api/agent/traces/${encodeURIComponent(traceId)}/replay`,
  ),
  traceStats: (limit = 100) => apiRequest<Record<string, unknown>>(`/api/agent/traces/stats?limit=${limit}`),
  opsSummary: (limit = 100) => apiRequest<Record<string, unknown>>(`/api/agent/ops/summary?limit=${limit}`),
  opsEvidence: (limit = 20) => apiRequest<Record<string, unknown>>(`/api/agent/ops/evidence?limit=${limit}`),
  evalReports: (limit = 10) => apiRequest<Array<Record<string, unknown>>>(`/api/agent/evals/reports?limit=${limit}`),
  evalEvents: () => apiRequest<Array<Record<string, unknown>>>('/api/agent/evals/events'),
  runRegressionEval: () => apiRequest<Record<string, unknown>>('/api/agent/evals/regression', { method: 'POST' }),
  runAdversarialEval: () => apiRequest<Record<string, unknown>>('/api/agent/evals/adversarial', { method: 'POST' }),
}
