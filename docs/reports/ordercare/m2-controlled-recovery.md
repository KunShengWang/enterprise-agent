# OrderCare M2：受控恢复闭环证据

> 本文保留 M2 阶段证据。M3 UNKNOWN 对账、Action 租约和崩溃恢复现已完成，当前状态见 [M3 故障正确性报告](m3-fault-correctness.md)。

> 验证日期：2026-07-17
>
> 阶段结论：`PASSED / Resume Ready`
> 能力边界：完成 happy-path 与安全门禁；尚未完成 UNKNOWN 对账和崩溃恢复

## 1. 业务闭环

M2 把 M1 的只读诊断扩展为一条真实纵向切片：

```text
自然语言异常描述
-> FlowOrder 权威案例聚合
-> Agent 解释事实和 SOP
-> FlowOrder 创建不可变 Proposal
-> Runtime 绑定服务端预演快照并暂停审批
-> 人工批准具体 Proposal 版本
-> FlowOrder 使用 actionRequestId 幂等提交原始消息
-> RecoveryConvergenceChecker 有界回查
-> 返回 proposalStatus / actionStatus / caseOutcome
```

模型不负责判断交易前置条件、生成业务幂等键、保存审批事实、循环轮询或宣布恢复成功。

## 2. 关键工程约束

### 2.1 三个标识、三个责任

- `toolExecutionId`：Runtime 一次工具执行的追踪与工具层幂等键。
- `proposalId`：一份不可变预演和审批对象；由 preview 的真实工具执行 ID 稳定派生。
- `actionRequestId`：FlowOrder 一次业务副作用命令的幂等键。

FlowOrder 是 Proposal 与 Action 的事实源；enterprise-agent 只保存同 Run 绑定和不可变审计副本。

### 2.2 审批绑定具体快照

模型调用 execute 时只提供 `proposalId`。`OrderCareApprovalRequestPreparer` 在创建审批前重新读取 FlowOrder Proposal，并将以下服务端事实写入 ApprovalRecord：

```text
proposalVersion
stateFingerprint
effectsDigest
warningsDigest
previewDigest
expiresAt
effects / warnings
actionRequestId
```

审批后恢复的是这一次原始 ToolCall，不让模型重新生成参数。Proposal 过期或状态漂移时，原审批失效。

### 2.3 命令状态与业务结果分离

```text
proposalStatus = APPROVED
actionStatus   = SUBMITTED
caseOutcome    = RESOLVED
```

`SUBMITTED` 只证明重放命令可靠提交。`RESOLVED` 还要求扣减为 RELEASED、库存不变量成立、相关死信终结，并且 FlowOrder 诊断为 ALREADY_CONVERGED。

## 3. 实现边界

M2 没有修改 `DefaultAgentRuntime.run()`，也没有增加 OrderCare 专用 Controller 或第二套 Run 状态机。接入点是：

```text
OrderCareExecutionProfileFactory
OrderCareToolCatalog
OrderCareRecoveryToolHandler
OrderCareApprovalRequestPreparer
OrderCareProposalBindingStore
RecoveryConvergenceChecker
HttpFlowOrderClient
ApprovalToolCallRequestPreparer
DefaultAgentToolRuntime
```

`DefaultAgentToolRuntime` 只增加通用的审批请求准备扩展；OrderCare 的快照绑定规则位于场景适配器中。

## 4. 自动化证据

### 4.1 enterprise-agent 全量回归

```powershell
mvn -q clean test
```

总计 47 条测试，失败和错误均为 0；4 个依赖真实外部环境的 E2E 默认跳过。

### 4.2 真实 PostgreSQL 暂停与恢复

```powershell
$env:ORDERCARE_RUNTIME_E2E='true'
mvn -q "-Dtest=OrderCareControlledRecoveryRuntimeE2ETests" test
```

验证：

- inspect 和 preview 已完成后进入 `WAITING_APPROVAL`；
- FlowOrder Proposal 被绑定到真实 ToolExecution/Run；
- ApprovalRecord 保存版本、指纹、digest、effects 和 warnings；
- 人工批准后恢复原 ToolCall；
- execute 只调用一次；
- 最终 `convergence.status=RESOLVED`。

### 4.3 FlowOrder 真实业务 E2E

FlowOrder 侧运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ordercare/m2-controlled-recovery.ps1 `
  -Action E2E -DbPassword <password>
```

该测试使用真实 MySQL、Nacos、order-service 和 RabbitMQ，观察到 `OrderStateConsumer` 消费 `ORDERCARE-M05-STATE-MESSAGE`，随后 Proposal 从 ACTIVE/NOT_STARTED/NOT_CONVERGED 收敛到 APPROVED/SUBMITTED/RESOLVED；重复 execute 返回相同结果。

组合回归为 33 tests、0 failures、0 errors。

## 5. 真实模型 Eval

```powershell
$env:ORDERCARE_MODEL_EVAL='true'
mvn -q "-Dtest=OrderCareM2ModelEvalE2ETests" test
```

结果：

```text
passed                 = 10 / 10
passRate               = 1.000
toolCallSuccessRate    = 1.000
toolPrecision          = 1.000
forbiddenViolationRate = 0.000
```

十条用例覆盖只读诊断、两类预演、完整 HITL、跳过审批攻击、force/SQL 越权、SOP-only、事实与 SOP 双证据及无恢复证据场景。

首轮为 7/10。两条安全请求被输入 Guardrail 正确提前阻断，但 Eval 错误要求继续调用工具；另一条在“SUBMITTED 不等于业务已恢复”的否定句中命中朴素禁词。修复只调整 adversarial 标签和禁词语境，没有降低 80% 门槛，也没有改变 Runtime 行为。

## 6. 单窗口演示

Vue Runtime Workbench 现在在同一页面展示：

- FlowOrder 案例事实、领域诊断、证据和硬风险；
- Proposal 版本、权威目标、影响、警告、有效期和摘要；
- 服务端绑定的审批快照、审批人和审批意见；
- proposalStatus、actionStatus、caseOutcome 与确定性收敛结果。

前端生产构建 `npm.cmd run build` 已通过。

## 7. 当前不能宣称的能力

M2 只达到 Resume Ready，当前不能宣称：

- 写响应丢失后自动查 Action 并恢复；
- Proposal/Action EXECUTING 租约和多实例接管；
- enterprise-agent 进程退出后的 OrderCare 自动 reconciliation；
- 重复 resume、网络超时和崩溃窗口下的完整故障注入；
- 生产级认证、mTLS、租户隔离或 SLO。

推荐简历表述：

> 基于自研 Java Agent Runtime 与 FlowOrder 构建异常订单受控恢复闭环：通过强类型工具聚合订单、扣减、库存和死信事实，由领域服务生成不可变 Proposal；高风险执行绑定版本化预演并经 HITL 审批，使用业务幂等键提交原消息，最后由确定性代码验证业务收敛。真实 PostgreSQL/MySQL/RabbitMQ E2E 与 10 条模型 Eval 通过。
