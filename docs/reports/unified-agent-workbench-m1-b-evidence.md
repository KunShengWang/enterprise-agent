# Unified Agent Workbench V1 — M1-B 路由基础证据

> 结论：PASSED  
> 日期：2026-07-20 CST  
> 蓝图：V0.2.3 / FINAL  
> 范围：Router / WorkCommandClassifier / Routing Recovery；不含 Adapter、Dispatch、统一页面和 M2 Projector

## 1. 实现结论

- 原始用户输入先写入 `agent_work_input`，并保存服务端认证主体的不可变角色快照；恢复扫描不从请求体重建身份。
- `WorkCommandClassifier` 与 `UnifiedTaskRouter` 完全分离；按钮/协议短路不调用模型，模型分类独立记录模型、Token、延迟、digest、Trace 和失败。
- `NORMAL_GOAL` 与 `START_NEW_WORK` 经审计后统一创建稳定 WorkItem；其他 WorkCommand 不创建新 WorkItem。
- Target Catalog 只有 `GENERAL_AGENT`、`ORDERCARE_CASE`、`INCIDENT_INVESTIGATION`、`INCIDENT_RECOVERY_PLAN` 四项，且按服务端 Feature Flag 和 Principal 角色裁剪。
- Router 只返回强类型 `ExecutionDecision`；Java `RoutePolicyValidator` 再生成 `ValidatedExecutionInput` 和 disposition。
- `modelConfidence` 仅落审计；模型推断的危险标识不能自动通过；Incident 调查固定进入 `WAITING_CONFIRMATION`。
- WorkItem 在 Router 前已经提交并持有不可替换的 `routingRequestId`；全部 attempt 复用该 ID。
- 路由 attempt 支持 `STARTED / RESULT_UNKNOWN / FAILED_ATTEMPT / EFFECTIVE / SUPERSEDED`，数据库部分唯一索引保证一个 WorkItem 最多一个 EFFECTIVE decision。
- Router 结果返回但持久化状态未知时，stale 恢复将原 attempt 标记 `RESULT_UNKNOWN`，计入可配置 Token 预留，再创建一个有界新 attempt。
- Router 失败保留可观测模型、Prompt/输出 digest、Token、延迟和 failure code；达到两次上限后退出 `ROUTING` 进入 `MANUAL_REVIEW`。
- M1-B 默认 Feature Flag 关闭，避免旧入口或无 Workbench 数据库凭据的部署自动启动扫描；启用后 Scanner 才执行。
- Router 不创建 Run、Incident、Recovery Plan 或 WorkLink，不写 `agent_run_state`，因此不会污染业务 Agent Run 指标。

## 2. 自动化证据

### 2.1 单元门禁

命令：

```powershell
mvn.cmd -Dtest=M1BRoutingUnitTests test
```

结果：`6 tests, 0 failures, 0 errors, 0 skipped`。

覆盖：

- deterministic command 的模型调用数和 Token 均为 0；
- model classifier 严格结构化输出及成本审计；
- Catalog 只有四项目标，General 使用 `general-safe-v1`；
- Incident 固定确认与危险标识来源校验；
- fallback fail-closed；
- 非法 JSON 的 Token、digest 与独立 failure code 不丢失。

### 2.2 PostgreSQL 与故障恢复门禁

命令：

```powershell
$env:WORKBENCH_POSTGRES_IT = "true"
$env:AGENT_STORAGE_POSTGRES_PASSWORD = "1234"
mvn.cmd '-Dtest=JdbcWorkbenchStorePostgresIT,JdbcRoutingStorePostgresIT' test
```

结果：`24 tests, 0 failures, 0 errors, 0 skipped`，其中 M1-A 19 条、M1-B 5 条。

M1-B 证据包括：

- 重复路由只调用模型一次且只有一个 EFFECTIVE decision；
- Router 后/Decision 前故障使用同一 WorkItem 和 routingRequestId 恢复；
- 原 attempt 为 RESULT_UNKNOWN，新 attempt 唯一生效，未知结果 Token 预留计入总成本；
- 两次结构化失败保留 80 Token，耗尽后进入 MANUAL_REVIEW；
- Incident 未确认时停在 WAITING_CONFIRMATION，`dispatchRequestId` 为空且 WorkLink 数为 0；
- Scanner 仅从已持久化的 tenant、principal 和角色快照恢复可信主体。

### 2.3 真实模型门禁

命令：

```powershell
$env:WORKBENCH_REAL_MODEL_IT = "true"
mvn.cmd -Dtest=M1BRealModelRoutingIT test
```

结果：真实 DeepSeek Provider，`3 tests, 0 failures, 0 errors, 0 skipped`，fallback 被显式关闭。

通过样本：

- 自然语言“继续暂停任务”输出 `RESUME_ACTIVE_WORK`，并返回真实模型名、Token 和 Prompt digest；
- Java CAS 解释目标路由到 `GENERAL_AGENT`，Java Validator 判定 `AUTO_DISPATCH`；
- 显式批次和队列的事故调查路由到 `INCIDENT_INVESTIGATION`，Java Validator 强制 `REQUIRE_CONFIRMATION`。

第一次真实模型连通测试使用了同时包含“保留旧任务”和“新建任务”的复合输入，模型选择 `PAUSE_ACTIVE_WORK`，不满足单标签断言。该样本属于蓝图规定的复合命令拆分问题，不用于伪造单标签通过；M1-B 连通门禁改用无歧义命令，复合样本保留给 M1-E 路由 Eval。

### 2.4 全量回归

命令：

```powershell
mvn.cmd test
```

结果：`152 tests, 0 failures, 0 errors, 11 existing environment-gated skips`。

Workbench Router 默认关闭后，Spring 上下文回归不再因缺少 PostgreSQL 密码触发后台 Scanner 错误。日志中的 `DefaultAgentRuntimeStateTests` 异常堆栈是既有测试主动注入并断言 Run 收敛到 FAILED 的预期证据，不是测试失败。

## 3. 范围与架构审计

- `DefaultAgentRuntime.run()`：未修改。
- Controller / 旧 API：未修改。
- ExecutionAdapter / DispatchCoordinator / DispatchReconciler：未实现。
- 前端 / SSE / WorkEvent Projector：未实现。
- 本机 HTTP 反调 Controller：未使用。
- Router Trace 独立保存在 command/routing decision，不创建业务 Agent Run。
- `git diff --check`：通过。

## 4. M1-B Definition of Done

| 门禁 | 结果 |
|---|---|
| 输入先落库、命令与新目标分流 | PASSED |
| deterministic/model command 独立审计 | PASSED |
| 四目标受限 Catalog 与权限裁剪 | PASSED |
| 真实模型强类型路由 | PASSED |
| Java 标识来源、风险与确认策略 | PASSED |
| WorkItem-before-Router 与稳定 routingRequestId | PASSED |
| attempt 生命周期、唯一 EFFECTIVE、Token 累计 | PASSED |
| stale ROUTING 与 RESULT_UNKNOWN 恢复 | PASSED |
| Incident 未确认零 Dispatch/零 WorkLink | PASSED |
| 全量回归和范围审计 | PASSED |

最终判定：`M1-B PASSED`。允许在本地 checkpoint 后重新读取冻结蓝图并进入 M1-C。
