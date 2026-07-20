import { API_BASE_URL, apiRequest, jsonBody } from './http'
import type { RoutePreview, UnifiedInputResponse, WorkEvent, WorkExecutionTree, WorkFocus, WorkInput, WorkItem, WorkItemDetail } from '../types/workbench'

const id = () => crypto.randomUUID()

export const workbenchApi = {
  submit: (conversationId: string, content: string) => apiRequest<UnifiedInputResponse>(
    `/api/agent/conversations/${encodeURIComponent(conversationId)}/inputs`,
    { method: 'POST', headers: { 'Idempotency-Key': `ui-${id()}` }, ...jsonBody({ content, metadata: { uiSource: 'unified-workbench' } }) },
  ),
  workItems: (conversationId: string) => apiRequest<WorkItem[]>(`/api/agent/conversations/${encodeURIComponent(conversationId)}/work-items`),
  inputs: (conversationId: string) => apiRequest<WorkInput[]>(`/api/agent/conversations/${encodeURIComponent(conversationId)}/inputs`),
  focus: (conversationId: string) => apiRequest<WorkFocus>(`/api/agent/conversations/${encodeURIComponent(conversationId)}/focus`),
  detail: (workItemId: string) => apiRequest<WorkItemDetail>(`/api/agent/work-items/${encodeURIComponent(workItemId)}`),
  executionTree: (workItemId: string) => apiRequest<WorkExecutionTree>(
    `/api/agent/work-items/${encodeURIComponent(workItemId)}/execution-tree`,
  ),
  events: (workItemId: string, afterSequence = -1, limit = 500) => apiRequest<WorkEvent[]>(
    `/api/agent/work-items/${encodeURIComponent(workItemId)}/events?afterSequence=${afterSequence}&limit=${limit}`,
  ),
  streamUrl: (workItemId: string, afterSequence: number, afterRunSequence: number) =>
    `${API_BASE_URL}/api/agent/work-items/${encodeURIComponent(workItemId)}/events/stream?afterSequence=${afterSequence}&afterRunSequence=${afterRunSequence}`,
  switchFocus: (conversationId: string, workItemId: string, expectedVersion: number) => apiRequest<WorkFocus>(
    `/api/agent/conversations/${encodeURIComponent(conversationId)}/focus`,
    { method: 'PUT', ...jsonBody({ workItemId, expectedVersion, clientInputId: `focus-${id()}` }) },
  ),
  confirm: (workItemId: string, preview: RoutePreview) => apiRequest<WorkItem>(
    `/api/agent/work-items/${encodeURIComponent(workItemId)}/confirm-route`,
    { method: 'POST', ...jsonBody({ ...preview, clientInputId: `confirm-${id()}` }) },
  ),
  reject: (workItemId: string, previewId: string) => apiRequest<WorkItem>(
    `/api/agent/work-items/${encodeURIComponent(workItemId)}/reject-route`,
    { method: 'POST', ...jsonBody({ previewId, clientInputId: `reject-${id()}` }) },
  ),
}
