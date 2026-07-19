import { API_BASE_URL, apiRequest, jsonBody } from './http'
import type {
  IncidentAggregate,
  IncidentInvestigationRequest,
  IncidentStartResponse,
  IncidentTrace,
  IncidentRecoveryPlan,
  RecoveryPlanStartRequest,
  RecoveryPlanStartResponse,
} from '../types/incident'

export const incidentApi = {
  start(request: IncidentInvestigationRequest) {
    return apiRequest<IncidentStartResponse>('/api/incidents/investigate', {
      method: 'POST',
      ...jsonBody(request),
    })
  },
  find(incidentId: string) {
    return apiRequest<IncidentAggregate>(`/api/incidents/${encodeURIComponent(incidentId)}?eventLimit=1000`)
  },
  trace(incidentId: string) {
    return apiRequest<IncidentTrace>(`/api/incidents/${encodeURIComponent(incidentId)}/trace`)
  },
  startRecoveryPlan(incidentId: string, request: RecoveryPlanStartRequest) {
    return apiRequest<RecoveryPlanStartResponse>(
      `/api/incidents/${encodeURIComponent(incidentId)}/recovery-plans`,
      { method: 'POST', ...jsonBody(request) },
    )
  },
  recoveryPlans(incidentId: string) {
    return apiRequest<IncidentRecoveryPlan[]>(
      `/api/incidents/${encodeURIComponent(incidentId)}/recovery-plans`,
    )
  },
  decideRecoveryItem(incidentId: string, planId: string, itemId: string, approved: boolean, reviewer: string, reason: string) {
    return apiRequest<IncidentRecoveryPlan>(
      `/api/incidents/${encodeURIComponent(incidentId)}/recovery-plans/${encodeURIComponent(planId)}/items/${encodeURIComponent(itemId)}/decision`,
      { method: 'POST', ...jsonBody({ approved, reviewer, reason }) },
    )
  },
  streamUrl(incidentId: string, afterSequence = -1) {
    return `${API_BASE_URL}/api/incidents/${encodeURIComponent(incidentId)}/events/stream?afterSequence=${afterSequence}`
  },
}
