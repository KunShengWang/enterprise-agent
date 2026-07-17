# OrderCare M3 故障正确性与 Eval 证据

## 结论

OrderCare 已达到 **Interview Strong**：在 M2 的诊断、不可变 Proposal、人工审批、幂等执行和业务收敛闭环之上，补齐了写响应丢失、`EXECUTING_TOOL` 进程崩溃、重复 resume、FlowOrder Action 执行租约和确定性对账。

这里的“恢复”不是让模型自由重试。LLM 只理解意图和解释结果；是否补发、是否接管、是否收敛由 Java 与 FlowOrder 权威状态决定。

M4 的身份认证、服务间认证、正式迁移治理、真实多节点部署和告警仍未完成，因此不宣称生产级。

## 故障恢复状态机

```text
execute transport error
  -> UNKNOWN（调用方结果）
  -> GET original actionRequestId
     -> SUBMITTED + RESOLVED       : 返回 RESOLVED
     -> SUBMITTED + NOT_CONVERGED  : 原 Action 对账，不补发
     -> EXECUTING + active lease   : 等待，不补发
     -> EXECUTING + expired lease  : FlowOrder 以原 Action CAS 接管
     -> NOT_STARTED                : 原 ToolCall 与审批参数最多补发一次
     -> cannot prove               : MANUAL_REVIEW
```

FlowOrder 持久化动作状态与业务结果状态分开：

```text
actionStatus = NOT_STARTED / PREVIEWED / EXECUTING / SUBMITTED / FAILED / MANUAL_REVIEW
caseOutcome  = ALREADY_CONVERGED / RESOLVED / NOT_CONVERGED / MANUAL_REVIEW
```

`UNKNOWN` 是 enterprise-agent 对一次网络调用结果的描述，不伪造成 FlowOrder Action 的最终状态。

## 核心实现

enterprise-agent：

- `RecoveryOutcomeReconciler`：有界查询原 Action；只在 `NOT_STARTED` 时按原参数补发一次。
- `OrderCareUncertainExecutionResolver`：从持久化 Proposal Binding 与 ToolExecution 恢复原审批请求。
- `DefaultAgentToolRuntime.reconcileUncertain`：通用不确定工具扩展点，负责持久化成功、失败或人工复核结果。
- `DefaultAgentRuntime.recoverExecutingTool`：在已有崩溃恢复点调用通用 resolver；`run()` 主循环没有 OrderCare 分支。
- `HttpFlowOrderClient`：读接口可有限重试，execute/reconcile 写请求不盲目重试。

FlowOrder：

- Recovery Action 增加 `executionOwner`、`executionLeaseUntil`、`lastHeartbeatAt`、`reconcileCount`、`reconciledAt`。
- 活跃租约拒绝第二执行者；过期租约使用同一个 `actionRequestId` CAS 接管。
- 业务已收敛但动作日志仍 EXECUTING 时补记 SUBMITTED；死信 REPLAYING 时等待；无法证明时转 MANUAL_REVIEW。
- `GET /internal/recovery/actions/{actionRequestId}` 返回权威动作事实。
- `POST /internal/recovery/actions/{actionRequestId}/reconcile` 执行确定性对账。

## 自动化故障证据

### 1. execute 已生效但 HTTP 响应丢失

`OrderCareM3ResponseLostRuntimeE2ETests` 使用真实 PostgreSQL Runtime 和 HTTP 故障桩：FlowOrder 接收 execute 后主动断开响应。验证结果：

```text
executeCount     = 1
actionQueryCount >= 1
responseLost     = true
reconciled       = true
actionRequestId  = act-ordercare-m3
重复 resume 后 executeCount 仍为 1
```

持久化关联证据：

```text
runId            = f1d167ef-70a2-4040-82f6-c6ea198a4cd6
inspectToolId    = 4e7659dd-6391-4e98-91fe-01bfa66b164f
previewToolId    = 38e04174-4271-49cd-bb71-5df28636a550
executeToolId    = 101bd0c3-d1f7-4184-8911-16d71f00b411
approvalId       = e52ab0af-652c-4e4f-ab99-8c8772f7cfac
proposalId       = prop-3d45c917-6751-3f0e-b4f0-37d40738bf6d
actionRequestId  = act-ordercare-m3
run state/phase  = COMPLETED / FINISHED
```

### 2. Runtime 在 EXECUTING_TOOL 后崩溃

`OrderCareM3CrashRecoveryRuntimeE2ETests` 在真实 PostgreSQL 构造完整的 Run、Timeline ToolCall、RUNNING ToolExecution 和 Proposal Binding，再由新 Runtime 调用 `resume`：

```text
runId           = ordercare-m3-crash-run-63064e65-9c99-483c-a93b-8080b6634ebd
toolExecutionId = tool-ordercare-m3-crash-63064e65-9c99-483c-a93b-8080b6634ebd
actionRequestId = act-ordercare-m3-crash-63064e65-9c99-483c-a93b-8080b6634ebd
resumeCount     = 1
final state     = COMPLETED / FINISHED
reconciled      = true
recoveredAfterCrash = true
executeCount    = 0
```

这证明新进程复用了原 Action，而不是重新执行副作用。

### 3. FlowOrder 真实消息链路

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/ordercare/m3-fault-recovery.ps1 `
  -Action E2E -DbPassword $env:FLOWORDER_MYSQL_PASSWORD
```

`RecoveryProposalHttpE2ETest` 在真实 MySQL、Nacos、RabbitMQ 和 order-service 下通过，验证 Proposal 执行、订单状态消息消费、Action 查询和重复 reconcile：

```text
proposalStatus       = APPROVED
actionStatus         = SUBMITTED
caseOutcome          = RESOLVED
reconciliationStatus = RESOLVED
actionRequestId      = 原值
```

恢复域测试报告共 42 条，失败 0、错误 0。

## 真实模型 Eval

执行：

```powershell
$env:ORDERCARE_MODEL_EVAL='true'
mvn -q "-Dtest=OrderCareM3ModelEvalE2ETests" test
```

2026-07-17 最终 DeepSeek 报告：

```text
evalRunId              = 701dbbc3-e004-4cbb-b78a-74f1ce6e2050
total/passed           = 20 / 20
passRate               = 1.000
averageScore           = 0.990
keywordRecall          = 1.000
toolCallSuccessRate    = 1.000
toolPrecision          = 1.000
toolRecall             = 0.947
ragUsageAccuracy       = 1.000
groundednessRate       = 1.000
forbiddenViolationRate = 0.000
hallucinationRiskRate  = 0.000
adversarialPassRate    = 1.000
```

20 条覆盖 4 条标识/澄清、7 条诊断、4 条安全/HITL、3 条未知结果与恢复、2 条对抗输入。受控恢复用例实际调用：

```text
floworder_case_inspect
-> floworder_recovery_preview
-> floworder_recovery_execute
-> Runtime WAITING_APPROVAL
```

评测采用分层裁判：结构化 Tool/RAG Trace 决定 groundedness，LLM Judge 评语义正确性；结构化 Guardrail 状态优先于“是否逐字说出拒绝”这类脆弱关键词。

## 默认回归与前端

```text
enterprise-agent mvn test：64 tests，0 failures，7 external E2E skipped
frontend npm run build：通过
```

外部 E2E 默认关闭，显式设置 `ORDERCARE_RUNTIME_E2E=true` 或 `ORDERCARE_MODEL_EVAL=true` 后运行。

## 面试可用结论与边界

可以说：

> 支持写请求结果未知、进程崩溃和重复恢复场景下，使用原 actionRequestId、执行租约和业务回查完成幂等对账；只有业务事实收敛后才报告 RESOLVED。

不能说：

- 已在真实多节点和网络分区下证明线性一致；
- 已完成生产身份认证、mTLS、SLO 和告警；
- 20 条 Eval 等于覆盖全部线上输入；
- 本地故障注入等于生产级灾备。
