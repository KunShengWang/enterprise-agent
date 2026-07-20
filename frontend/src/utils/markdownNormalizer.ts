function isJsonDocument(source: string) {
  const trimmed = source.trim()
  if (!(trimmed.startsWith('{') && trimmed.endsWith('}'))
    && !(trimmed.startsWith('[') && trimmed.endsWith(']'))) return false
  try { JSON.parse(trimmed); return true } catch { return false }
}

function normalizeLine(line: string) {
  const heading = line.match(/^(\s{0,3}#{1,6})([^\s#].*)$/)
  if (heading) return `${heading[1]} ${heading[2]}`
  const list = line.match(/^(\s*)([-+*])([^\s\-+*].*)$/)
  if (list) return `${list[1]}${list[2]} ${list[3]}`
  return line
}

export function normalizeMarkdown(source: string) {
  if (!source || isJsonDocument(source)) return source
  const lines = source.replace(/\r\n?/g, '\n').split('\n')
  const normalized: string[] = []
  let fence = ''

  for (const original of lines) {
    const fenceMatch = original.match(/^\s*(`{3,}|~{3,})/)
    if (fenceMatch) {
      if (!fence) fence = fenceMatch[1][0]
      else if (fenceMatch[1][0] === fence) fence = ''
      normalized.push(original)
      continue
    }
    if (fence) {
      normalized.push(original)
      continue
    }
    const line = normalizeLine(original)
    const blockStart = /^\s{0,3}(#{1,6}\s|[-+*]\s|>\s|\|.*\|\s*$)/.test(line)
    const previous = normalized.at(-1) ?? ''
    if (blockStart && normalized.length && previous.trim()
      && !/^\s*(#{1,6}\s|[-+*]\s|>\s|\|.*\|\s*$)/.test(previous)) normalized.push('')
    normalized.push(line)
  }
  return normalized.join('\n')
}
