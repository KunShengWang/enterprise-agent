# Unified Agent Workbench M3-B Gap Matrix

更新时间：2026-07-20

## 当前事实

| 层级 | 已有能力 | 缺口 |
|---|---|---|
| Router | attempt、实际 Token、RESULT_UNKNOWN token reserve、最多两次调用 | 调用前没有 WorkItem 总预算准入 |
| WorkItem | WorkLink、Run/Incident 执行树可聚合指标 | 没有持久化总账、预留、并发 CAS 和耗尽状态 |
| Run | `AgentRunBudget` 强制模型/工具/Token/成本/时长并随 checkpoint 恢复 | 不知道上层 WorkItem/Incident 剩余额度 |
| Incident | 每个角色有独立 `AgentExecutionProfile` | Commander/Specialist/Reviewer/Planner 之间没有聚合上限 |

## M3-B 交付

1. 持久化 Budget Account 和 Reservation Ledger；
2. WorkItem 创建后按配置建立总预算，Router 每个 attempt 调用前预留、结束后结算；
3. Dispatch 前按 ExecutionTarget 预留保守子预算；
4. Incident/Recovery Plan 建立子账户，角色 Run 创建前预留 profile 上界，结束后按 `AgentRunBudgetSnapshot` 结算；
5. 重试、恢复、追问使用原账户累计，不重置；
6. 配置禁用、缺失、非法或剩余额度不可确定时，Incident/Plan/副作用路径 fail-closed；
7. 预算耗尽使用独立错误和事件，不伪装成 Guardrail；
8. 已提交副作用和 UNKNOWN 对账不受“禁止创建新 Run”影响。

## 不做

- 不修改 `DefaultAgentRuntime.run()`；
- 不承诺精确账单金额，成本仍为配置价格下的估算；
- 不在 M3-B 实现 Projector/Task lease 接管；
- 不把预算放进模型 Prompt 让模型自行遵守；
- 不因预算耗尽回滚已提交动作。
