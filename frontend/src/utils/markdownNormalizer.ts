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

const fenceLanguages = [
  'typescript', 'javascript', 'markdown', 'properties', 'plaintext',
  'java', 'json5', 'json', 'kotlin', 'python', 'shell', 'powershell',
  'yaml', 'xml', 'html', 'css', 'scss', 'sql', 'bash', 'text', 'vue',
]

function splitAttachedFences(source: string) {
  const output: string[] = []
  let insideFence = false
  for (const line of source.replace(/\r\n?/g, '\n').split('\n')) {
    const marker = line.includes('```') ? '```' : line.includes('~~~') ? '~~~' : ''
    if (!marker) { output.push(line); continue }
    const index = line.indexOf(marker)
    if (insideFence) {
      if (index > 0 && !line.slice(index + marker.length).trim()) {
        output.push(line.slice(0, index).trimEnd(), marker)
        insideFence = false
      } else {
        output.push(line)
        if (!line.slice(index + marker.length).trim()) insideFence = false
      }
      continue
    }
    const before = line.slice(0, index).trimEnd()
    const tail = line.slice(index + marker.length)
    const language = fenceLanguages.find(value => tail.toLowerCase().startsWith(value))
    if (!language) { output.push(line); continue }
    const remainder = tail.slice(language.length)
    if (before) output.push(before)
    output.push(`${marker}${language}`)
    if (remainder.trim()) output.push(remainder.trimStart())
    insideFence = true
  }
  return output
}

const javaStatementAfterComment = /^(\s*\/\/.*?)(\s+)((?:(?:public|private|protected)\s+|(?:class|interface|record|enum)\s+|(?:Object|ObjectFactory(?:<[^>]+>)?|Map<[^>]+>|List<[^>]+>|Set<[^>]+>|Supplier<[^>]+>|Optional<[^>]+>)\s+[A-Za-z_$][\w$]*\s*=|[A-Za-z_$][\w$]*\s*=|(?:this\.)?[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+\s*\().*)$/

function normalizeJavaComment(line: string) {
  return line.replace(/^(\s*\/\/)(?=\S)/, '$1 ')
}

function normalizeJavaFenceLine(line: string) {
  const trailingComment = line.match(/^(\s*.+;)(\s+)(\/\/.*)$/)
  if (trailingComment) return [
    trailingComment[1],
    normalizeJavaComment(`${line.match(/^\s*/)?.[0] ?? ''}${trailingComment[3]}`),
  ]

  const mergedComment = line.match(javaStatementAfterComment)
  if (!mergedComment) return [normalizeJavaComment(line)]
  const indentation = line.match(/^\s*/)?.[0] ?? ''
  return [normalizeJavaComment(mergedComment[1].trimEnd()), `${indentation}${mergedComment[3]}`]
}

function javaStructure(line: string) {
  return line
    .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, '')
    .replace(/\/\/.*$/, '')
}

function normalizeJavaFence(lines: string[]) {
  const indents = lines
    .filter(line => line.trim())
    .map(line => line.match(/^\s*/)?.[0].length ?? 0)
  const isFlat = indents.length >= 3 && Math.max(...indents) - Math.min(...indents) <= 1
  const repaired = lines.flatMap(normalizeJavaFenceLine)
  if (!isFlat) return repaired

  let depth = 0
  return repaired.map((line) => {
    const trimmed = line.trim()
    if (!trimmed) return ''
    const structure = javaStructure(trimmed)
    const leadingClosers = structure.match(/^}+/)?.[0].length ?? 0
    depth = Math.max(0, depth - leadingClosers)
    const formatted = `${'    '.repeat(depth)}${trimmed}`
    const opens = (structure.match(/{/g) ?? []).length
    const closes = (structure.match(/}/g) ?? []).length - leadingClosers
    depth = Math.max(0, depth + opens - closes)
    return formatted
  })
}

export function normalizeMarkdown(source: string) {
  if (!source || isJsonDocument(source)) return source
  const repairedLists = source.replace(/([^\s\n])\s*-\s+(?=\*\*)/g, '$1\n- ')
  const lines = splitAttachedFences(repairedLists)
  const normalized: string[] = []
  let fence = ''
  let fenceLanguage = ''
  let fenceBuffer: string[] = []

  for (let index = 0; index < lines.length; index += 1) {
    const original = lines[index]
    const fenceMatch = original.match(/^\s*(`{3,}|~{3,})([\w+-]*)/)
    if (fenceMatch) {
      if (!fence) {
        fence = fenceMatch[1][0]
        fenceLanguage = fenceMatch[2].toLowerCase()
        fenceBuffer = []
      } else if (fenceMatch[1][0] === fence) {
        if (fenceLanguage === 'java') normalized.push(...normalizeJavaFence(fenceBuffer))
        fence = ''
        fenceLanguage = ''
        fenceBuffer = []
      }
      normalized.push(original)
      continue
    }
    if (fence) {
      if (fenceLanguage === 'java') fenceBuffer.push(original)
      else normalized.push(original)
      continue
    }
    const line = normalizeLine(original)
    const attachedTable = line.match(/^(\s{0,3}#{1,6}\s+)([^|]+)(\|.+\|)\s*$/)
    const nextLineIsTableDelimiter = /^\s*\|?\s*:?-{3,}\s*\|/.test(lines[index + 1] ?? '')
    if (attachedTable && nextLineIsTableDelimiter) {
      normalized.push(`${attachedTable[1]}${attachedTable[2].trim()}`, '', attachedTable[3])
      continue
    }
    const headingBody = line.match(/^(\s{0,3}#{1,6}\s+[^*].*?)(\*\*.+)$/)
    if (headingBody) {
      normalized.push(headingBody[1].trimEnd(), '', headingBody[2])
      continue
    }
    const blockStart = /^\s{0,3}(#{1,6}\s|[-+*]\s|>\s|\|.*\|\s*$)/.test(line)
    const previous = normalized.at(-1) ?? ''
    if (blockStart && normalized.length && previous.trim()
      && !/^\s*(#{1,6}\s|[-+*]\s|>\s|\|.*\|\s*$)/.test(previous)) normalized.push('')
    normalized.push(line)
  }
  if (fence && fenceLanguage === 'java') normalized.push(...normalizeJavaFence(fenceBuffer))
  return normalized.join('\n')
}
