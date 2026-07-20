# Unified Agent Workbench V1 - M2-C 聊天内 Multi-Agent 执行树 Evidence

> 日期：2026-07-20 CST
> 蓝图：V0.2.3 / FINAL
> 前置门禁：M2-B PASSED（`9b662d3`）

## 1. 交付结论

M2-C 已将现有权威 Run、Incident、Task、Evidence、Conflict、Assessment 和 Recovery Plan 事实投影为 WorkItem-scoped 强类型执行树。统一工作台无需跳转专项页即可查看：

```text
Deterministic Coordinator (synthetic, model calls = 0)
-> Commander
-> Specialist Attempt 1..N
-> Reviewer
-> Recovery Planner
```

General/OrderCare 使用单 Agent 树；Recovery Plan WorkItem 只显示自己的 Planner 与 Plan，不把父调查 Agent 冒充为当前 WorkItem 的执行节点。

## 2. 权威数据边界

新增只读端点：

```http
GET /api/agent/work-items/{workItemId}/execution-tree
```

服务端必须先使用 `AuthenticatedPrincipal` 查询 owned WorkItem 和持久化 WorkLink，随后才能读取 link 指向的 Incident/Run/Plan。客户端不能直接传 incidentId、runId 或 planId 构造执行树，因此该端点不是领域 Store 的查询旁路。

执行树不持久化第二份事实，也不修改：

- Incident 调度、重试、WAITING_INPUT 和 lease；
- Runtime Run/Trace；
- Evidence/Conflict/Assessment；
- Recovery Plan/Proposal/Approval；
- WorkEvent Schema 和 sequence。

存在多个 PRIMARY WorkLink 时投影 fail-closed，不使用 `findFirst()` 猜测执行根。

## 3. 强类型执行树

### 3.1 Coordinator

- 复用 `IncidentTraceProjector.syntheticCoordinatorSpan`；
- `synthetic=true`；
- `modelCalls=0`；
- `syntheticCoordinatorModelCalls=0`；
- 不创建 `agent_run_state`。

### 3.2 Agent Node

每个节点包含：

- role、taskId、runId；
- attempt/maxAttempts；
- status、objective、error；
- 独立 TraceRun；
- modelCalls/toolCalls/Token/cost/duration；
- 归属于该 childRunId/taskId 的 Evidence。

已创建但尚无 childRunId 的 Specialist/Planner 仍显示为待执行节点；没有 Runtime Trace 的历史 Run 显示 `TRACE_UNAVAILABLE`，不会伪造阶段或 Token。

### 3.3 结构化产物

- Conflict 只读取 `EVIDENCE_CONFLICT_DETECTED` TaskEvent；
- Reviewer Assessment 只读取 Incident `assessment_json`；
- Recovery Proposal/审批/动作/结果只读取 Recovery Plan 记录；
- UI 不重新运行 Conflict Checker，也不生成模型结论。

## 4. 前端体验

统一工作台新增：

- synthetic Coordinator 行；
- Commander/Specialist/Reviewer/Planner 节点；
- 成功、失败、运行中、待执行四态颜色；
- Attempt、当前阶段、模型轮次、工具、Token、耗时；
- 可展开的 Runtime 阶段、工具 Span 和 Evidence；
- Java Conflict、Reviewer Assessment、Recovery Proposal 卡片。

执行树以 5 秒状态刷新为兜底，并在 Incident/Run/Plan WorkEvent 到达时主动刷新。M2-B SSE cursor 和主回答增量协议保持不变。

## 5. 自动化证据

### 5.1 投影与 Controller 单元

```powershell
mvn.cmd '-Dtest=UnifiedWorkExecutionTreeServiceTests,UnifiedWorkControllerTests' test
```

结果：10 tests，0 failures，0 errors，0 skipped。其中执行树专项覆盖：

- Coordinator synthetic 且模型调用 0；
- Specialist Attempt 1/2 同时保留；
- Evidence 按 child Run 归属；
- Conflict 与 Assessment 权威投影；
- Recovery Plan WorkItem 只含 Planner；
- General Run 使用单 Agent 树；
- 外租户/未知 WorkItem 不触达领域 Store；
- 多 PRIMARY Link fail-closed。

### 5.2 真实 PostgreSQL + Runtime timeline

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:AGENT_STORAGE_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5432/enterprise_agent'
$env:AGENT_STORAGE_POSTGRES_USERNAME = 'postgres'
$env:AGENT_STORAGE_POSTGRES_PASSWORD = '1234'
mvn.cmd '-Dtest=UnifiedWorkExecutionTreePostgresIT' test
```

结果：1 test，0 failures，0 errors，0 skipped。真实写入并读取：

- owned WorkItem + PRIMARY INCIDENT Link；
- Incident 与 synthetic Coordinator；
- Commander、Specialist Attempt 1/2、Reviewer 四个 Runtime Run；
- 5 次独立模型轮次；
- 2 条 Evidence；
- 1 条 Java Conflict；
- Reviewer Assessment。

断言 Coordinator model calls 为 0、Attempt 为 `[1, 2]`、树总模型轮次为 5。

### 5.3 PostgreSQL 全门禁

```powershell
$env:WORKBENCH_POSTGRES_IT = 'true'
$env:INCIDENT_POSTGRES_IT = 'true'
mvn.cmd '-Dtest=*PostgresIT' test
```

最终结果：56 tests，0 failures，0 errors，0 skipped。

首次运行时，本轮为 M2-B 页面健康检查启动的本地 Router/Projector 仍在后台运行，异步修改 M2-A Fixture，造成旧测试期望 3、实际 4。停止该进程后，未修改断言，原 M2-A 专项与全部 56 条 PostgreSQL 测试均通过。该问题属于测试环境隔离，不是通过降低门禁解决。

### 5.4 全量与前端

```powershell
mvn.cmd clean test
cd frontend
npm.cmd run build
```

结果：后端 180 tests，0 failures，0 errors，11 skipped；11 条均为仓库既有外部 E2E 环境门禁。前端 `vue-tsc -b` 与 Vite production build 通过。

应用内浏览器自动化仍因本机运行时资源路径错误不可用，因此没有视觉截图；没有使用其他浏览器工具伪造验收。

## 6. Schema、事务与故障证据

- migration：无；
- 多 PRIMARY Link：fail-closed；
- 缺失 Runtime Trace：保留真实身份/状态，指标为 0；
- 缺失 childRunId：显示待执行节点，不伪造 Run；
- 投影查询失败：不改变底层 WorkItem/Incident/Plan；
- Coordinator Run 行：0；
- Coordinator 模型调用：0。

## 7. 范围审计

- `DefaultAgentRuntime.run()`：未修改；
- Incident/Recovery 调度代码：未修改；
- WorkEvent/SSE 协议：未重解释；
- M2-D 历史页面回放：未实现；
- M3 命令、预算、claim/lease：未提前实现；
- push：未执行；
- `git diff --check`：通过。

## 8. 未完成项

- 页面刷新、服务重启和历史 WorkItem 的完整执行树回放回归属于 M2-D。
- 现有 Runtime/Incident 专项页面的兼容性和统一页面响应式视觉回归将在 M2-D 固化。
- 浏览器自动化环境恢复后应补桌面/移动视口截图，但不改变 M2-C 权威数据判定。

最终判定：`M2-C PASSED`。Checkpoint 为本报告所在的本地提交。
