import { API_BASE_URL, ApiError } from './http'
import type { AgentRequest, AgentStreamEvent, ApiResponse } from '../types/agent'

interface StreamOptions {
  signal?: AbortSignal
  onEvent: (event: AgentStreamEvent) => void
}

function parseEventBlock(block: string): AgentStreamEvent | null {
  const data = block
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('\n')

  if (!data || data === '[DONE]') {
    return null
  }

  try {
    return JSON.parse(data) as AgentStreamEvent
  } catch (error) {
    throw new ApiError('无法解析 Agent SSE 事件', 'INVALID_SSE_EVENT', 0, { data, error })
  }
}

async function consumeAgentStream(path: string, options: StreamOptions, body?: unknown): Promise<void> {
  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
  }
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: options.signal,
  })

  if (!response.ok || !response.body) {
    const raw = await response.text()
    let message = `SSE 连接失败（HTTP ${response.status}）`
    let code = 'SSE_CONNECTION_FAILED'
    try {
      const payload = JSON.parse(raw) as ApiResponse<unknown>
      message = payload.message || message
      code = payload.code || code
    } catch {
      // 保留 HTTP 状态描述；响应可能不是 JSON。
    }
    throw new ApiError(message, code, response.status, raw)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value, { stream: !done })
    buffer = buffer.replace(/\r\n/g, '\n')

    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const event = parseEventBlock(block)
      if (event) {
        options.onEvent(event)
      }
      boundary = buffer.indexOf('\n\n')
    }

    if (done) {
      break
    }
  }

  if (buffer.trim()) {
    const event = parseEventBlock(buffer.trim())
    if (event) {
      options.onEvent(event)
    }
  }
}

export async function streamAgentEvents(request: AgentRequest, options: StreamOptions): Promise<void> {
  await consumeAgentStream('/api/agent/runs', options, request)
}

export async function resumeAgentEvents(runId: string, options: StreamOptions): Promise<void> {
  await consumeAgentStream(
    `/api/agent/runs/${encodeURIComponent(runId)}/resume/events`,
    options,
  )
}
