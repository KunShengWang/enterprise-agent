# OrderCare Incident Command Phase 2 实施与验证报告

> 日期：2026-07-19
> 状态：`IMPLEMENTED / LOCAL_REGRESSION_PASSED / EXTERNAL_POSTGRES_E2E_PASSED`

## 1. 已实现闭环

```text
IncidentAssessment(ASSESSED)
-> 独立 Recovery Planner Run
-> incident-recovery-plan-v1
-> Java fail-closed 校验
-> FlowOrder 单目标不可变 Proposal
-> 每项独立 Approval
-> Recovery Plan version CAS claim
-> 原 actionRequestId 幂等执行
-> UNKNOWN 原命令对账
-> ConvergenceChecker
-> RESOLVED / PARTIAL / REJECTED / MANUAL_REVIEW
```

Phase 1 的 Commander、Specialist 和 Reviewer 仍然只读。Planner 没有 Capability，也不能调用执行工具；它只输出候选 `ProposalRequest`。Java 不提供“模型失败后的恢复计划兜底”，避免确定性代码在证据不足时擅自制造写候选。

## 2. 关键可靠性约束

- 只接受 `IncidentStatus=ASSESSED + IncidentOutcome=ASSESSED`；
- `HIGH` 风险、`OPEN HIGH` 冲突或任意 EvidenceGap 均禁止规划；
- V1 只允许 `REQUEST_ID + REPLAY`，最多 5 项；
- 目标必须属于不可变 IncidentSnapshot；
- 每项必须引用 Assessment 已引用的 ACCEPTED FACT；
- 每项必须有未截断、scopeHash 一致并包含目标 requestId 的 DEAD_LETTER_SET；
- proposalId、approvalId 和 itemId 从 planId/clientItemKey 稳定派生；
- Approval 绑定 planId、itemId、assessmentDigest、proposalId 和 previewDigest；
- execute 前重新读取 Proposal 并比较 version/fingerprint/effects/warnings/preview digest；
- 逐项 CAS claim，禁止一键盲批和 FlowOrder 批量写；
- 响应未知时只对账原 actionRequestId；
- 接口成功不等于业务成功，最终由确定性 ConvergenceChecker 判定。

## 3. 代码入口

- `IncidentRecoveryPlanner`：模型规划、Java 校验、FlowOrder preview 和 Approval 创建；
- `IncidentRecoveryPlanValidator`：Assessment、范围、Evidence 和动作白名单；
- `IncidentRecoveryExecutionService`：审批绑定、CAS claim、execute、UNKNOWN 与收敛；
- `JdbcIncidentRecoveryPlanStore`：requestKey 幂等和 Recovery Plan version CAS；
- `IncidentCommandView.vue`：同一窗口的计划、不可变预演和逐项审批；
- `ordercare-incident-command-phase2.sql`：PostgreSQL DDL。

## 4. 已通过验证

```powershell
mvn.cmd -q '-Dtest=IncidentRecoveryPlannerTests,IncidentRecoveryExecutionServiceTests,IncidentRecoveryPlanValidatorTests,RecoveryPlanDigestTests' test
mvn.cmd -q '-Dtest=IncidentRecoveryPlannerEvalTests' test
mvn.cmd -q test
cd frontend
npm.cmd run build
```

验证覆盖：合法规划、范围扩展、任意标识符、越权写动作、虚构 Evidence、缺少 Evidence、重复目标、重复 clientItemKey、超计划预算、稳定 requestKey、逐项审批拒绝、重复批准只执行一次，以及前端类型/构建回归。

当前干净全仓报告：`129 tests / 0 failures / 0 errors / 11 skipped`。其中 Phase 2 新增并已执行的无外部依赖测试为 25 条；跳过项包含显式环境变量门禁的真实 PostgreSQL、真实模型和纵向集成测试。

## 5. 外部门禁结果

2026-07-19 PostgreSQL 启动后，执行了真实数据库 CAS 门禁与完整 Runtime 纵向 E2E：

```powershell
$env:INCIDENT_POSTGRES_IT = "true"
$env:INCIDENT_COMMAND_E2E = "true"
$env:AGENT_STORAGE_POSTGRES_PASSWORD = "1234"

mvn.cmd -q '-Dtest=JdbcIncidentRecoveryPlanStorePostgresIT,IncidentCommandRuntimeE2ETests' test
```

结果：`JdbcIncidentRecoveryPlanStorePostgresIT` 1/1 通过；`IncidentCommandRuntimeE2ETests` 4/4 通过。纵向场景覆盖 Phase 1 调查、权威 Assessment 引用、独立 Planner Run、不可变 Proposal、人工批准、原 actionRequestId 执行、确定性收敛与 Trace 投影。首次执行还证明 Planner 会拒绝未被权威 Assessment 引用的 Evidence；修正测试 Reviewer 的真实引用语义后放行，生产校验规则未被放宽。

## 6. 后续状态

多实例 Recovery Plan/Task lease、stale 回收、崩溃接管、fencing token 和 kill switch 已在 Incident Command V1.5 Phase 3 可靠性内核中实现并独立验收。证据见 [Phase 3 报告](incident-command-phase3-evidence.md)。自动告警接入、统一身份和完整租户治理仍属于外部部署扩展。
