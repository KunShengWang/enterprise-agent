export interface IncidentInvestigationRequest {
  alertBatchId: string
  alertType: string
  detectedAt: string
  symptom: string
  candidateRequestIds: string[]
  queueNames: string[]
}

export interface IncidentStartResponse {
  incidentId: string
  status: string
  createdAt: string
}

export interface IncidentRecord {
  incidentId: string
  commanderRunId?: string
  reviewerRunId?: string
  status: string
  snapshot: Record<string, unknown>
  delegationPlan: Record<string, unknown>
  assessment: Record<string, unknown>
  clarificationCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface IncidentTask {
  taskId: string
  role: string
  objective: string
  status: string
  attempt: number
  maxAttempts: number
  childRunId?: string
  lastError?: string
  outputSummary: Record<string, unknown>
}

export interface IncidentEvidence {
  evidenceId: string
  taskId: string
  childRunId: string
  evidenceClass: string
  evidenceSubtype: string
  sourceSystem: string
  observedAt: string
  facts: Record<string, unknown>
  status: string
}

export interface IncidentEvent {
  eventId: string
  eventSequence: number
  eventType: string
  eventCategory: string
  actorType: string
  actorId: string
  senderRole?: string
  recipientRole?: string
  payload: Record<string, unknown>
  createdAt: string
}

export interface IncidentAggregate {
  incident: IncidentRecord
  tasks: IncidentTask[]
  evidence: IncidentEvidence[]
  events: IncidentEvent[]
}

export interface IncidentTrace {
  incidentId: string
  syntheticCoordinatorSpan: Record<string, unknown>
  childRuns: Array<{ runRole: string; taskId: string; trace: Record<string, unknown> }>
  modelMetrics: Record<string, unknown>
}

export interface IncidentStreamItem {
  type: 'EVENT' | 'HEARTBEAT'
  cursor: number
  event?: IncidentEvent
  emittedAt: string
}
