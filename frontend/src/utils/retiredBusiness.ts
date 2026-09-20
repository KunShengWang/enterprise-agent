import type { WorkItem, WorkExecutionTree, RoutePreview } from '../types/workbench'

export const retiredMessage = '业务已退役、不能继续执行；仅保留已保存的历史记录。'
export function isRetiredTarget(target?: string) {
  return ['ORDERCARE_CASE', 'INCIDENT_INVESTIGATION', 'INCIDENT_RECOVERY_PLAN'].includes(target ?? '')
}
export function isRetiredTool(tool?: string) {
  return /^(floworder_|mcp\.floworder\.)/.test(tool ?? '')
    || ['delegate_order_analyst', 'delegate_inventory_analyst', 'delegate_mq_analyst', 'review_incident_evidence'].includes(tool ?? '')
}
export function isRetiredPreview(preview?: RoutePreview) {
  const input = preview?.payload?.validatedInput as Record<string, unknown> | undefined
  return isRetiredTarget(preview?.targetId) || Boolean(input?.scopeSnapshotId)
}
export function isRetiredWork(work?: Pick<WorkItem, 'activeExecutionTarget'>, tree?: WorkExecutionTree | null) {
  return isRetiredTarget(work?.activeExecutionTarget) || tree?.treeType === 'RETIRED'
}
export function isRetiredRun(request?: { scenarioId?: string; metadata?: Record<string, unknown> }, tool?: string) {
  return /^(ordercare-|incident-)/.test(request?.scenarioId ?? '')
    || isRetiredTarget(String(request?.metadata?.executionTarget ?? '')) || isRetiredTool(tool)
}
export function executionTargetLabel(target: string) {
  if (isRetiredTarget(target)) return '已退役业务（只读历史）'
  return ({ PROCUREMENT_SOURCING: '采购 Agent', GENERAL_AGENT: 'General（受限通用）' } as Record<string, string>)[target] || target || 'Routing'
}
