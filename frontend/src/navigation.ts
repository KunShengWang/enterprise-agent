/**
 * 导航结构的单一来源。
 *
 * 之前 AppSidebar 与 WorkbenchTaskSidebar 各自内联一份数组，两处已经不一致
 * （例如 Run 历史页 /runs 在两处都没有入口，是孤儿页）。这里统一。
 *
 * 分层依据（Q14 决定）：主导航只留工作台；其余产品页收进「更多」，
 * 避免左栏堆成 8 项把会话列表挤没。
 */
export interface NavItem {
  to: string
  label: string
  hint: string
}

/** 常驻左栏顶部的主入口。 */
export const primaryNav: NavItem = { to: '/', label: '工作台', hint: '对话与执行' }

/** 收进「更多」菜单的产品页。Run 历史此前没有任何入口，这里补上。 */
export const moreNav: NavItem[] = [
  { to: '/approvals', label: '审批中心', hint: 'HITL 决策与恢复' },
  { to: '/capabilities', label: '能力地图', hint: 'Tool 与 Skill' },
  { to: '/knowledge', label: '知识与记忆', hint: 'RAG 与 Memory' },
  { to: '/observability', label: '可观测性', hint: 'Trace · Eval · Ops' },
  { to: '/api-lab', label: '接口实验室', hint: '完整 API 地图' },
  { to: '/runs', label: 'Run 历史', hint: '回放与轨迹' },
]

/** 底部固定入口。 */
export const settingsNav: NavItem = { to: '/settings', label: '设置', hint: '主题与偏好' }
