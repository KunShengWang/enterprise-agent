import type { ApiResponse } from '../types/agent'

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly details: unknown

  constructor(message: string, code = 'REQUEST_FAILED', status = 0, details: unknown = null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }
}

export async function apiRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  headers.set('Accept', 'application/json')

  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers })
  const raw = await response.text()
  let payload: ApiResponse<T> | null = null

  if (raw) {
    try {
      payload = JSON.parse(raw) as ApiResponse<T>
    } catch {
      throw new ApiError(`服务返回了非 JSON 响应（HTTP ${response.status}）`, 'INVALID_RESPONSE', response.status, raw)
    }
  }

  if (!response.ok || !payload?.success) {
    throw new ApiError(
      payload?.message || `请求失败（HTTP ${response.status}）`,
      payload?.code || 'HTTP_ERROR',
      response.status,
      payload,
    )
  }

  return payload.data
}

export function jsonBody(value: unknown): Pick<RequestInit, 'body'> {
  return { body: JSON.stringify(value) }
}
