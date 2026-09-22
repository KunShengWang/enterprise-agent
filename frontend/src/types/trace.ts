export interface RuntimeTraceSpan {
  spanId: string
  traceId: string
  parentSpanId?: string
  name: string
  kind: string
  status: string
  summary: string
  startedAt: string
  endedAt?: string
  durationMs: number
  input: string
  output: string
  error: string
  attributes: Record<string, unknown>
}

export interface RuntimeTraceEvent {
  occurredAt: string
  stage: string
  detail: string
}

export interface RuntimeReplayEvent {
  sequence: number
  occurredAt: string
  eventType: string
  summary: string
  payload: Record<string, unknown>
}

export interface RuntimeRunTrace {
  traceId: string
  conversationId: string
  question: string
  status: string
  startedAt: string
  endedAt?: string
  durationMs: number
  failureReason: string
  estimatedPromptTokens: number
  estimatedCompletionTokens: number
  estimatedCost: number
  spans: RuntimeTraceSpan[]
  events: RuntimeTraceEvent[]
  replayEvents: RuntimeReplayEvent[]
  metrics: Record<string, unknown>
}
