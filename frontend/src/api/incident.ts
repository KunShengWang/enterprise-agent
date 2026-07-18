import { API_BASE_URL, apiRequest, jsonBody } from './http'
import type {
  IncidentAggregate,
  IncidentInvestigationRequest,
  IncidentStartResponse,
  IncidentTrace,
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
  streamUrl(incidentId: string, afterSequence = -1) {
    return `${API_BASE_URL}/api/incidents/${encodeURIComponent(incidentId)}/events/stream?afterSequence=${afterSequence}`
  },
}
