# Unified Agent Workbench V1 — M1-C Evidence

> 日期：2026-07-20 CST
> 蓝图：V0.2.3 / FINAL
> 前置门禁：M1-B PASSED（`ae68824`）

## 1. 设计要求与实现事实

M1-C 完成四个冻结目标的受限 `ExecutionAdapter`、稳定 `dispatchRequestId`、不可变 Incident Preview、显式确认、派发状态机和结果未知对账。Adapter 直接调用既有应用服务，不通过本机 HTTP 反调 Controller，也没有新增第五个执行目标。

四个目标映射如下：

| 目标 | Adapter | 权威目标幂等键 |
|---|---|---|
| GENERAL_AGENT | `GeneralAgentExecutionAdapter` | `agent_run_state.dispatch_request_id` |
| ORDERCARE_CASE | `OrderCareExecutionAdapter` | `agent_run_state.dispatch_request_id` |
| INCIDENT_INVESTIGATION | `IncidentInvestigationExecutionAdapter` | `agent_incident.dispatch_request_id` |
| INCIDENT_RECOVERY_PLAN | `IncidentRecoveryPlanExecutionAdapter` | 既有 `(incident_id, request_key)`，其中 requestKey=`dispatchRequestId` |

`ExecutionAdapterRegistry` 启动时要求四个且仅四个冻结 Adapter，缺失或重复均 fail-fast。General 不注入场景 Profile；OrderCare 固定使用 `ordercare-floworder-v1`。Incident 使用 WorkItem 创建时间作为稳定 `detectedAt`，避免重试时范围哈希漂移。

## 2. Schema

迁移文件：`docs/sql/unified-agent-workbench-m1-c.sql`。

- `agent_run_state.dispatch_request_id`：可空、部分唯一索引；
- `agent_incident.dispatch_request_id`：可空、部分唯一索引；
- `agent_route_preview`：一个 WorkItem 一份不可变 Preview，保存 version、validatedInputDigest、scopeDigest、expiresAt 和确认人；
- `agent_dispatch_attempt`：保存 attemptNo、reconciliation、目标、状态、失败码与时间；
- 每个 WorkItem 最多一个 `EFFECTIVE` 派发 attempt。

旧入口创建的数据允许 `dispatch_request_id` 为空，因此迁移保持向后兼容。

## 3. 核心状态机

```text
ROUTED
  ├─ 低风险且校验通过 → READY_TO_DISPATCH
  └─ Incident → WAITING_CONFIRMATION
                   ├─ Preview 过期/篡改 → 拒绝确认
                   ├─ 人工拒绝 → ABANDONED / REJECTED
                   └─ 绑定版本与双 digest 确认 → READY_TO_DISPATCH

READY_TO_DISPATCH → DISPATCHING → DISPATCHED
                         ├─ 目标结果未知 → RESULT_UNKNOWN → reconcile
                         └─ 有界重试耗尽 → MANUAL_REVIEW
```

未确认 Incident 只创建 Preview，不调用 Incident Adapter，因此 Commander、Specialist、Reviewer Run 数均为 0。

## 4. 事务与故障边界

- `claimDispatch` 在 PostgreSQL 行锁内完成 WorkItem CAS、旧 STARTED attempt 标记 `RESULT_UNKNOWN`、新 attempt 分配和 `DISPATCHING` 推进。
- Adapter 使用相同 `dispatchRequestId` 先查后建；目标侧唯一键是最终防线。
- `completeDispatch` 在同一个本地事务中写入 EFFECTIVE attempt、唯一 WorkLink、WorkItem `DISPATCHED` 状态和 WorkEvent。
- 注入“目标已创建、WorkLink 落库前崩溃”后，新协调器先查询目标事实源，只补 WorkLink，不创建第二个目标。
- 路由已经写为 EFFECTIVE 后，Preview 后处理异常不会反向把路由 attempt 标为失败。
- Preview 并发创建发生唯一键竞争时读取胜出记录，避免产生伪失败。

M1-C 只承诺单实例有界协调；多实例 claim/lease、执行器崩溃接管和跨源投影属于后续 M3/M2 门禁。

## 5. 自动化测试

### 5.1 M1-B 回归与 M1-C 单元

```powershell
mvn.cmd '-Dtest=M1BRoutingUnitTests,ExecutionAdapterUnitTests' test
```

结果：M1-B 6/6；M1-C Adapter 4/4；0 failure，0 error，0 skipped。

覆盖四个 Adapter 的冻结目标、General/OrderCare Profile、受保护派发元数据、Incident 稳定时间与范围、Recovery Plan 复用既有 requestKey，以及 Registry 缺失/重复 fail-fast。

### 5.2 PostgreSQL 真实集成

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:AGENT_STORAGE_POSTGRES_PASSWORD = '1234'
mvn.cmd '-Dtest=JdbcWorkbenchStorePostgresIT,JdbcRoutingStorePostgresIT,JdbcDispatchStorePostgresIT,DispatchTargetIdempotencyPostgresIT' test
```

结果：30/30，0 failure，0 error，0 skipped。其中 M1-C 新增 6 条 PostgreSQL 测试：

- 重复派发只产生一个目标、一个 WorkLink、一个 EFFECTIVE attempt；
- 目标创建后、WorkLink 前崩溃可恢复，且不产生第二目标；
- Incident 未确认时 Adapter 调用数为 0；
- Preview 版本/digest 篡改与过期确认均被拒绝；
- `agent_run_state` 对同一 dispatchRequestId 拒绝第二个 Run；
- `agent_incident` 对同 key 同 scope 返回原 Incident，对 scope 漂移 fail-closed。

### 5.3 全量回归

```powershell
mvn.cmd test
```

最终结果：156 tests，0 failures，0 errors，11 skipped。11 条均为既有显式环境门禁测试；本轮没有新增未解释 skip。测试日志中的 Runtime 异常栈是既有故障注入用例的预期输出，不是失败。

## 6. 真实基础设施与副作用说明

PostgreSQL 17 使用本地真实实例完成 Schema、唯一索引、事务、CAS 和故障恢复验证。M1-C 不要求真实模型路由（M1-B 已有 3/3 真实 DeepSeek 证据），也不要求 RabbitMQ 或 FlowOrder 执行恢复副作用。本轮没有对未知生产环境执行订单恢复；四 Adapter 以隔离单元测试和真实目标 Store 幂等测试形成证据。

## 7. 范围审计

- `DefaultAgentRuntime.run()`：未修改；
- Controller / 旧 API：未修改；
- 前端、统一 Controller、SSE、跨源 Projector：未实现；
- 本机 HTTP 反调 Controller：未使用；
- 新 ExecutionTarget：未增加；
- 多实例 lease/claim：未宣称完成；
- `git diff --check`：通过；
- push：未执行。

## 8. 未完成项与风险

- 统一输入页面与确认 API 属于 M1-D；当前 M1-C 提供应用服务和持久化能力，没有提前开放 Controller。
- WorkEvent 跨源投影、统一 SSE 和历史回放属于 M2。
- Incident 异步执行进程崩溃后的跨实例接管属于 M3-C；本阶段只保证派发目标身份和 WorkLink 可对账。
- 旧 Agent API 仍可携带 metadata；统一入口在 M1-D 必须继续禁止客户端写入受保护的 `_workbenchDispatchRequestId`。

## 9. Definition of Done

| 门禁 | 结果 |
|---|---|
| 四个冻结 ExecutionAdapter | PASSED |
| stable dispatchRequestId 与目标侧唯一绑定 | PASSED |
| Incident Preview → Explicit Confirmation | PASSED |
| 未确认 Incident 零子 Run/零派发 | PASSED |
| READY_TO_DISPATCH → DISPATCHING → DISPATCHED | PASSED |
| 目标创建后、WorkLink 前崩溃恢复 | PASSED |
| 重复请求不创建第二 Run/Incident/Plan | PASSED |
| PostgreSQL 真集成与全量回归 | PASSED |
| 冻结范围审计 | PASSED |

最终判定：`M1-C PASSED`。Checkpoint 为本报告所在的本地提交，可通过 `git log -1 --oneline` 获取；提交完成且重新读取冻结蓝图后，允许进入 M1-D。
