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
