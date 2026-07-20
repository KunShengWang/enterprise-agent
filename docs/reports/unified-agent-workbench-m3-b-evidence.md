# Unified Agent Workbench M3-B Evidence

更新时间：2026-07-20 CST

## 结论

M3-B Router / WorkItem / Run / Incident 分层预算：**PASSED**。

预算现在是 Java 和 PostgreSQL 强制执行的工程边界，不是 Prompt 建议。`DefaultAgentRuntime` 的 Run 级硬预算保持不变；M3-B 在其上增加 WorkItem 总账、Router/Target 预留，以及 Incident/Recovery Plan 子账户。

## 四层预算

| 层级 | 准入与结算 |
|---|---|
| Router | 每个 attempt 在模型调用前使用 decisionId 预留；成功按实际 Token/时延/估算成本结算；调用前失败释放；结果未知保留上界 |
| WorkItem | 汇总 Router 和唯一 Target 的 consumed/reserved；Target 创建前保守预留；预算不足时不调用 Adapter |
| Run | 继续由 `AgentRunBudget` 强制模型、工具、Token、成本、时长；暂停、恢复和追问沿用原 snapshot |
| Incident | Commander、每个 Specialist attempt、Reviewer、Recovery Planner 和 Proposal RPC 使用稳定 operationKey 预留；终态按权威 Run snapshot/实际 RPC 结算 |

## 数据一致性

新增：

- `agent_budget_account`：不可变 owner/policy、五维最大值、reserved、consumed、status 和 version；
- `agent_budget_reservation`：稳定 operationKey、类别、预留、实际、RESERVED/SETTLED/RELEASED/DENIED；
- `UNIQUE(account_id, operation_key)`：HTTP/调度重试不重复计费；
- PostgreSQL `FOR UPDATE + version CAS`：多实例不能超卖同一账户；
- 第一次拒绝会持久化 DENIED 并将账户置为 EXHAUSTED，后续不再创建新模型/工具调用。

父子结算规则：

```text
WorkItem Target reservation
→ Incident/Plan child account
→ role/tool reservations
→ authoritative usage settlement
→ child reserved == 0 且业务到达可确定终态
→ 用 child consumed 结算父 Target reservation
```

发生 Runtime 异常、结果 UNKNOWN、预算数据库不可用或仍有活动 reservation 时，不释放父上界。

## Fail-Closed

- Budget 配置禁用时，只有 General 低风险只读路径可按显式配置降级；
- OrderCare、Incident Investigation、Recovery Plan 均拒绝创建目标；
- 配置层级非法、运行中策略漂移、父 WorkItem 未预留子预算均返回独立 `BUDGET_*` 原因；
- Router/Dispatch 把预算原因写入权威 attempt 和 WorkEvent，不伪装成 Guardrail；
- 预算耗尽只阻止新 Run/RPC，不回滚已提交副作用，也不停止 UNKNOWN 对账。

## 可观测接口

```text
GET /api/agent/work-items/{workItemId}/budget
```

该接口先校验认证 Principal 对 WorkItem 的所有权，再返回 maximum/reserved/consumed/status/version。底层 Budget Store 不直接暴露给 Controller。

## 自动化证据

### 单元与预算 Eval

```powershell
mvn.cmd -q "-Dtest=WorkbenchBudgetEvalSuiteTests,WorkbenchBudgetPolicyTests,BudgetAdmissionCoordinatorTests" test
```

- Budget boundary/cumulative Eval：15/15；
- Policy：3/3；
- Router/Dispatch 调用前阻断：2/2；
- failure/error：0/0。

### PostgreSQL

```powershell
$env:WORKBENCH_POSTGRES_IT = "true"
mvn.cmd -q "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" "-Dtest=*PostgresIT" test
```

- 68 tests；
- 实际执行 56；
- 既有外部环境 skipped 12；
- failure/error：0/0；
- M3-B Budget PostgreSQL：5/5。

预算 PostgreSQL 场景覆盖：实际结算、同 operation 幂等、耗尽持久化、双 Store 并发不超卖、Incident 父子预留与终态结算。

### 全量后端

```powershell
mvn.cmd -q clean "-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true" test
```

- 204 tests；
- failure/error：0/0；
- skipped：11 个既有环境 E2E；
- 实际执行：193。

### 前端

```powershell
cd frontend
npm.cmd run build
```

- `vue-tsc -b`：通过；
- Vite production build：通过；
- 已增加 WorkItem Budget 强类型查询契约。

## 范围约束

- 未修改 `DefaultAgentRuntime.run()`；
- 未让模型自报或自行管理预算；
- 未把预算耗尽当成审批、Guardrail 或业务恢复成功；
- 未实现 M3-C Projector/Task 多实例故障接管；
- 未执行 push。

## 下一门禁

M3-C：Projector、Routing/Dispatch、Incident Task 的多实例 claim/lease、崩溃接管与故障注入统一门禁。
