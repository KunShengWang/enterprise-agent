# Unified Agent Workbench V1 — M1-C 缺口矩阵

> 日期：2026-07-20 CST
> 蓝图：V0.2.3 / FINAL
> 前置门禁：M1-B PASSED（`ae68824`）

| 蓝图要求 | 当前代码事实 | 缺失内容 | M1-C 计划 | 测试/故障证据 | 设计分支 |
|---|---|---|---|---|---|
| 四个 ExecutionAdapter | General/OrderCare 共用 `RuntimeAgentExecutor`；Incident/Plan 有现成 Launcher | 统一强类型 Adapter 契约和受限 Registry | Adapter 直接调用应用服务，禁止本机 HTTP | 每个 Adapter 幂等 dispatch/reconcile | 无 |
| stable dispatchRequestId | AUTO route 已生成；确认路径尚未生成 | 确认事务中生成并冻结；Adapter 全程复用 | `DispatchStore` CAS 推进 | 重复调用仍同一目标 ID | 无 |
| General/OrderCare Run 幂等 | Run request metadata 可携带 key，但 Store 无唯一列/查询 | `agent_run_state.dispatch_request_id` 唯一索引与查询 | 不修改 `DefaultAgentRuntime.run()`；Store 从可信 metadata 提取 | 目标后/WorkLink 前崩溃可查询原 Run | 无；最小演进 Runtime Store |
| Incident 幂等创建 | `initialize()` 每次随机 incidentId 并立即启动子 Run | `agent_incident.dispatch_request_id` 唯一键；initializeForDispatch；查询 | Launcher 复用原 Incident；只在首次创建时调度 | 同 key 不创建第二 Incident | 无；应用服务内原子绑定 |
| Recovery Plan 幂等 | 已有 `(incidentId, requestKey)` 幂等 | 用 dispatchRequestId 作为 requestKey | Adapter 调用现有 Launcher/Store 查询 | 同 key 返回原 Plan | 无 |
| Incident Preview + Confirm | M1-B 只停在 WAITING_CONFIRMATION | 不可变 Preview、版本、validatedInputDigest、scopeDigest、过期、确认 CAS | Preview Store/Service；确认后才 READY | 未确认子 Run=0；篡改/过期拒绝 | 无 |
| READY→DISPATCHING→DISPATCHED | 尚无 Dispatch Store/Coordinator | attempt、CAS、事件、WorkLink 同事务 | `DispatchCoordinator` | 状态与事件一致 | 无 |
| 目标后/WorkLink 前崩溃 | 无 | RESULT_UNKNOWN、reconcile、同 key 重试 | `DispatchReconciler` 单实例有界扫描 | 注入崩溃后只补一个 Link | 无 |
| 重复目标异常 | 目标唯一键正常应避免 | 明确 duplicate/unknown fail-closed | Adapter 查询异常转 MANUAL_REVIEW | 人工复核门禁 | 无 |
| Principal/Tenant | M1-B 已持久化角色快照 | Scanner 继续使用可信快照 | 不接收请求体 userId/roles/profile | 跨租户/角色测试 | 无 |

## 范围

允许：Adapter、Preview/Confirmation、Dispatch attempt/Store/Coordinator/Reconciler、现有 Run/Incident 幂等键最小演进、测试和证据。

禁止：统一前端、Controller API、跨源 Projector/SSE、M2/M3、通用工作流、修改 `DefaultAgentRuntime.run()`、本机 HTTP 反调 Controller、真实恢复副作用。
