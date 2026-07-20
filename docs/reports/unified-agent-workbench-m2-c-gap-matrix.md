# Unified Agent Workbench M2-C 聊天内执行树缺口矩阵

> 基线：M2-B `9b662d3`；范围仅限现有权威执行事实的只读树投影与统一页面展示。

| 冻结要求 | 当前事实 | M2-C 补齐方式 | 门禁 |
|---|---|---|---|
| 聊天内 Multi-Agent 执行树 | Incident 专项页自行拼装 Trace/Task/Evidence | 新增 WorkItem-scoped 强类型执行树投影与只读端点 | 统一页无需跳转即可看清角色和阶段 |
| synthetic Coordinator | `IncidentTraceProjector` 已生成 synthetic span | 原样投影并固定 `modelCalls=0`，不得创建 coordinator Run | coordinator Run 行数与模型调用数均为 0 |
| Commander/Specialist/Reviewer/Planner | Run、Task、Plan 已有权威 Store | 按 WorkLink 和 Incident/Plan 事实组装节点 | 不把前端占位伪装成已执行 Run |
| Attempt 聚合 | Task 保存 first/current childRunId，Trace role 带 ATTEMPT | 节点显式携带 attempt/maxAttempts，同 taskId 聚合展示 | 同角色重试不覆盖前一 Attempt |
| 阶段、模型轮次、工具、Token | Runtime Trace 已包含 spans/events/replay/metrics | Agent 节点携带 Trace 与归一化指标 | 每个真实 Run 可独立审计 |
| Evidence | `agent_evidence` 关联 taskId/childRunId | Agent 节点分组引用，并保留树级完整 Evidence | 引用不丢失、不跨 Incident 混入 |
| Conflict | Java Checker 结果写入 Incident TaskEvent | 强类型 ConflictView 引用 event/payload/evidence IDs | 不由前端或模型重新判断冲突 |
| Reviewer Assessment | Incident `assessment_json` 为权威结果 | 只读投影到树级 Assessment 卡片 | 不接受无权威持久化结论 |
| Proposal/审批状态 | Recovery Plan Store 保存 Plan/Item/Proposal | 投影关联 Plan 记录，统一页只读展示 | 不增加审批或执行写入口 |
| General/OrderCare | WorkItem PRIMARY RUN 已可追踪 | 使用单 Agent 树，不伪装为 Multi-Agent | 现有单 Agent 页面保持可用 |
| Recovery Plan WorkItem | PRIMARY RECOVERY_PLAN link 指向单一 Plan | 只展示该 WorkItem 的 Planner Run 与 Plan，不冒充父调查 Agent | 一个 WorkItem 仍表达一个稳定目标 |
| 租户隔离 | WorkItem/Link 查询 principal-scoped，Incident Store 无租户 API | 必须先通过 owned WorkItem 和其已绑定 Link 再读领域事实 | 猜测 incidentId/planId 不可越权 |

## 允许修改

- Workbench 只读执行树 DTO、Assembler/Service、Controller 查询端点；
- Unified Workbench 执行树、Agent 节点、Evidence/Conflict/Assessment/Proposal 卡片；
- M2-C 单元、PostgreSQL、前端和证据文档。

## 禁止修改

- `DefaultAgentRuntime.run()`；
- Commander/Specialist/Reviewer/Planner 调度、重试、WAITING_INPUT 和 lease 语义；
- Incident/Recovery Plan 权威 Schema 和状态机；
- 创建 Coordinator Run 或把 synthetic span 计入模型指标；
- 为展示效果伪造 Evidence、Conflict、Assessment、Proposal 或 Token；
- M2-D 历史回放、M3 WorkCommand/预算/多实例能力。
