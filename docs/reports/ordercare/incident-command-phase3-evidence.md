# OrderCare Incident Command Phase 3 实施与验证报告

> 日期：2026-07-19
> 状态：`RELIABILITY_KERNEL_IMPLEMENTED / POSTGRES_FENCING_PASSED / RUNTIME_E2E_PASSED`

## 1. 已实现能力

```text
多实例 Task/Recovery Item claim
-> TTL lease + heartbeat
-> 单调 fencing token
-> version CAS + token 双重验权
-> stale scan
-> 新实例崩溃接管
-> 旧实例迟到结果拒绝
-> 原 actionRequestId 对账或 Task 有界重试
-> Incident 持久化检查点续跑
```

关键入口：

- `JdbcIncidentStore`：Task 原子 claim、续租、stale 查询、fenced result commit；
- `IncidentTaskScheduler`：模型调用期间心跳和带 token 的 Evidence 提交；
- `IncidentTaskLeaseRecoveryCoordinator`：stale Task 接管和父 Incident 续跑；
- `JdbcIncidentRecoveryPlanStore`：Recovery Item 原子 claim、续租、fenced terminal update；
- `IncidentPhase3RecoveryCoordinator`：恢复项 stale scan；
- `IncidentRecoveryExecutionService`：接管后只协调原 Proposal/actionRequestId；
- `GET /api/incidents/phase3/status`：运行状态；
- `POST /api/incidents/phase3/scan`：受控手动扫描。

## 2. 安全不变量

1. token 只在 claim/takeover 时递增，heartbeat 不改变 token；
2. 旧 owner 即使网络调用晚返回，也不能提交 Evidence 或 Recovery 终态；
3. Recovery takeover 不生成新 proposalId/actionRequestId；
4. Task takeover 增加 attempt，不能绕过 maxAttempts 和 Incident deadline；
5. Scanner 不持有模型连接或阻塞线程等待旧实例；
6. kill switch 阻止新副作用和自动接管；
7. Phase 3 默认关闭，不影响普通 Runtime、pause/resume、HITL 和 Phase 1/2 默认行为。

## 3. 验证命令

```powershell
$env:INCIDENT_POSTGRES_IT = "true"
$env:INCIDENT_COMMAND_E2E = "true"
$env:AGENT_STORAGE_POSTGRES_PASSWORD = "1234"

mvn.cmd -q '-Dtest=JdbcIncidentStorePostgresIT,JdbcIncidentRecoveryPlanStorePostgresIT' test
mvn.cmd -q '-Dtest=IncidentCommandRuntimeE2ETests' test
mvn.cmd -q clean test
cd frontend
npm.cmd run build
```

验证结果：

- Phase 3 + 真实 PostgreSQL 专项测试：`17 passed / 0 failed / 0 errors`；
- 默认全量回归：`130 tests / 0 failures / 0 errors / 11 environment-gated skipped`；
- 前端生产构建：通过。

真实 PostgreSQL 验证包括：Task stale lease 被第二 owner 接管、token 单调增加、旧 token Evidence 提交被拒绝、接管重试预算耗尽后确定性失败、Recovery Item stale lease 被接管、旧 owner 终态更新被拒绝。Runtime E2E 在 Phase 3 开启时覆盖 3 条 Phase 1 事故调查场景和 1 条 Proposal/HITL/execute/convergence 完整闭环。

## 4. 声明边界

本阶段完成的是应用内生产可靠性内核，不宣称已经接入企业告警平台、IAM、mTLS、完整租户配额、跨地域容灾或任意 DAG Workflow。这些能力依赖外部平台契约，不能用本地假实现冒充生产完成。
