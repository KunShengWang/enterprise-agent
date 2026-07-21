import type { WorkExecutionTree } from '../types/workbench'

type JsonObject = Record<string, unknown>

function object(value: unknown): JsonObject | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : null
}

function objects(value: unknown) {
  return Array.isArray(value) ? value.map(object).filter((item): item is JsonObject => Boolean(item)) : []
}

function text(value: unknown) {
  return typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : ''
}

function section(lines: string[], title: string, values: string[], empty: string) {
  lines.push(`## ${title}`, '')
  if (!values.length) lines.push(empty, '')
  else values.forEach(value => lines.push(`- ${value}`))
  lines.push('')
}

export function incidentAssessmentMarkdown(tree: WorkExecutionTree | null | undefined) {
  if (!tree || tree.executionTarget !== 'INCIDENT_INVESTIGATION') return ''
  const assessment = object(tree.assessment)
  if (!assessment || !Object.keys(assessment).length) return ''

  const outcome = text(assessment.outcome)
  if (outcome !== 'ASSESSED') return ''
  const riskLevel = text(assessment.riskLevel) || 'UNSPECIFIED'
  const incidentId = text(assessment.incidentId) || tree.executionId
  const facts = objects(assessment.confirmedFacts).map(item => text(item.statement)).filter(Boolean)
  const rootCauses = objects(assessment.rootCauseCandidates).map(item => text(item.hypothesis)).filter(Boolean)
  const recommendations = objects(assessment.recommendations).map(item => text(item.action)).filter(Boolean)
  const gaps = objects(assessment.evidenceGaps).map((item) => {
    const message = text(item.message)
    const source = text(item.sourceSystem)
    return [source, message].filter(Boolean).join('：')
  }).filter(Boolean)
  const assessmentConflicts = objects(assessment.conflicts).map((item) => {
    const type = text(item.conflictType) || '未命名冲突'
    const severity = text(item.severity)
    const status = text(item.status)
    return `${type}${severity ? `（${severity}）` : ''}${status ? `：${status}` : ''}`
  })
  const projectedConflicts = tree.conflicts.map(item =>
    `${item.conflictType}${item.severity ? `（${item.severity}）` : ''}${item.status ? `：${item.status}` : ''}`)
  const conflicts = assessmentConflicts.length ? assessmentConflicts : projectedConflicts

  const lines = [
    '# 事故调查 Assessment',
    '',
    `- **调查状态：** ${outcome}`,
    `- **风险等级：** ${riskLevel}`,
    `- **Incident ID：** \`${incidentId}\``,
    `- **证据数量：** ${tree.evidence.length}`,
    `- **冲突数量：** ${conflicts.length}`,
    '',
  ]
  section(lines, '已确认事实', facts, '当前 Assessment 没有确认可公开的业务事实。')
  section(lines, '根因候选', rootCauses, '现有证据不足以确认单一根因。')
  section(lines, '冲突检查', conflicts, 'Reviewer 未发现需要报告的证据冲突。')
  section(lines, '证据缺口', gaps, '未记录额外证据缺口。')
  section(lines, '建议', recommendations, '本次仅完成调查，不生成或执行恢复动作。')
  lines.push('> 本结果来自只读 Multi-Agent 调查的结构化 Assessment；未执行恢复、重放或其他业务写操作。')
  return lines.join('\n').trim()
}
