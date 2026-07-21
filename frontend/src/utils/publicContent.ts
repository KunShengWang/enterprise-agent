export function isToolCallProtocolEnvelope(content: string) {
  const candidate = content.trimStart()
  if (!candidate.startsWith('{')) return false
  try {
    const parsed = JSON.parse(candidate) as Record<string, unknown>
    return Array.isArray(parsed.toolCalls)
      || (Object.hasOwn(parsed, 'assistantText') && Object.hasOwn(parsed, 'toolCalls'))
  } catch {
    return candidate.includes('"assistantText"') && candidate.includes('"toolCalls"')
  }
}

export function normalizeAssistantContent(content: string) {
  const candidate = content.trimStart()
  if (!candidate.startsWith('{')) return content
  try {
    const parsed = JSON.parse(candidate) as Record<string, unknown>
    const keys = Object.keys(parsed)
    const allowedKeys = keys.every(key => key === 'assistantText' || key === 'toolCalls')
    const noEffectiveCalls = !Array.isArray(parsed.toolCalls) || parsed.toolCalls.length === 0
    return allowedKeys && noEffectiveCalls && typeof parsed.assistantText === 'string'
      ? parsed.assistantText : content
  } catch {
    return content
  }
}
