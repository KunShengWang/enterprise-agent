# OrderCare 实施状态与学习地图

> 更新时间：2026-07-17
> 当前阶段：M2 `PASSED`（Resume Ready），M3 准备开始
> 目标完成线：Interview Strong（M3）

## 1. 文档用途

[项目总蓝图](enterprise-agent-master-blueprint.md)描述最终目标，本文件只记录已经被当前代码、测试或运行证据证明的事实。没有证据的能力一律视为未完成，不能写入简历。

## 2. 阶段看板

| 阶段 | 状态 | 已有证据 | 下一门禁 |
|---|---|---|---|
| M0 设计收口 | `PASSED` | 总蓝图 V1.1、Agent/确定性代码责任边界、分级 DoD | 保持设计与实现同步 |
| M0.5 FlowOrder Recovery Baseline | `PASSED` | 15 条测试、固定夹具、双扫描器 CAS、真实 RabbitMQ 跨服务恢复 | 开始 M1 只读契约 |
| M1 只读智能诊断 | `PASSED` | 稳定 Case 契约、7 类诊断、统一 SSE Run、8/8 真实模型 Eval | Proposal/Action 双标识与不可变预演 |
| M2 受控恢复闭环 | `PASSED` | 不可变 Proposal、版本审批、同 Run 恢复、领域幂等、确定性收敛、真实 RabbitMQ E2E、10/10 模型 Eval | M3 UNKNOWN 对账与崩溃恢复 |
| M3 故障正确性 | `NOT_STARTED` | Runtime 已有部分 claim/恢复基础，但没有 OrderCare 证据 | UNKNOWN、执行租约、重启、响应丢失、重复 resume、20 条 Eval |
| M4 安全部署 | `NOT_STARTED` | FlowOrder 管理接口默认关闭 | 服务认证、用户身份、迁移脚本、部署边界 |

## 3. M0.5 放行证据

FlowOrder 权威报告：

```text
docs/reports/ordercare/m0.5-recovery-baseline.md
```

自动化结果：

```text
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

真实 E2E 结果：

```text
resource-service replay API -> 200
reservation.orderStatus     -> TIMEOUT
deduct.status               -> RELEASED
stock available/locked/sold -> 10/0/0
deadLetter.status           -> RESOLVED
consume log                 -> one order-state-consumer success row
```

M0.5 还顺带发现并修复两个非功能性缺口：FlowOrder 服务原来不能生成可执行 Spring Boot JAR；恢复管理接口依赖未保留的 Java 参数名，运行时会返回 500。

## 4. M1 放行证据

FlowOrder 提供版本化只读契约 `floworder-recovery-case-v1`，支持按 `REQUEST_ID`、`ORDER_NO`、`DEDUCT_NO`、`DEAD_LETTER_ID` 定位案例，并返回预约、订单、扣减、库存、死信、恢复动作、证据、硬风险和服务端候选动作。

确定性领域测试：

```text
RecoveryCaseServiceImplTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
```

真实 FlowOrder HTTP/Feign/MySQL E2E：

```text
RecoveryCaseHttpE2ETest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
schemaVersion    = floworder-recovery-case-v1
diagnosisCode    = REPLAY_CANDIDATE
factsComplete    = true
candidate.owner  = FLOWORDER
```

enterprise-agent 统一 Runtime/SSE E2E：

```text
OrderCareUnifiedRuntimeE2ETests
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
tool_requested -> tool_completed -> run_completed
toolName = floworder_case_inspect
同一 persisted run / traceId
```

2026-07-17 真实 DeepSeek 模型 Eval：

```text
evalRunId              = 857f4eef-5b08-495e-9382-b3a379aff788
passed                 = 8 / 8
passRate               = 1.000
averageScore           = 0.9875
toolCallSuccessRate    = 1.000
ragUsageAccuracy       = 1.000
groundednessRate       = 1.000
forbiddenViolationRate = 0.000
hallucinationRiskRate  = 0.000
```

首轮 Eval 只有 5/8。失败集中在“诊断后再查 SOP”“纯 SOP 咨询”和“拒绝绕过审批”三类。修复没有降低阈值，而是把能力选择规则明确到服务端 Profile，并修复 Eval 复跑复用固定会话导致的历史上下文污染；第二轮达到 8/8。完整证据见 [M1 只读智能诊断报告](reports/ordercare/m1-readonly-diagnosis.md)。

## 5. M2 放行证据

M2 没有把固定工作流塞进模型循环，而是形成以下责任链：

```text
LLM 识别意图并选择能力
-> FlowOrder 聚合事实并生成不可变 Proposal
-> Runtime 将服务端快照绑定到 ApprovalRecord
-> 人工批准具体版本、指纹、影响和警告
-> FlowOrder 使用 actionRequestId 幂等提交原始消息
-> RecoveryConvergenceChecker 有界回查业务收敛
-> LLM 只解释结构化结果
```

权威状态严格分离：

```text
proposalStatus = ACTIVE / APPROVED / REJECTED / EXPIRED / INVALIDATED
actionStatus   = NOT_STARTED / SUBMITTED / UNKNOWN
caseOutcome    = NOT_CONVERGED / RESOLVED / MANUAL_REVIEW
```

自动化与真实链路证据：

```text
FlowOrder M0.5 + M1 + M2 组合回归：33 tests，0 failures
FlowOrder RecoveryProposalHttpE2ETest：真实 MySQL + Nacos + RabbitMQ，1/1
enterprise-agent mvn clean test：47 tests，0 failures（其中 4 个外部依赖 E2E 默认跳过）
OrderCareControlledRecoveryRuntimeE2ETests：真实 PostgreSQL，暂停/审批/恢复，1/1
OrderCare M2 真实模型 Eval：10/10
toolCallSuccessRate = 1.000
toolPrecision       = 1.000
forbiddenViolation = 0.000
frontend npm run build：通过
```

首次真实消息 E2E 还发现了部署环境问题：旧 resource-service 消费者长期持有一条 `unacked` 测试消息，导致新实例无法收敛。通过 RabbitMQ 管理指标定位具体连接和消息后，只释放该测试连接并核验消息体，随后 E2E 观察到真实 `order-state-consumer` 日志并通过。这一证据说明测试覆盖了 broker 与多实例环境，而不只是 HTTP stub。

完整报告见 [M2 受控恢复闭环](reports/ordercare/m2-controlled-recovery.md)。

## 6. 核心学习主线保护

`DefaultAgentRuntime.run()` 是 Agent 学习主线，不承载 OrderCare 业务分支。开始 M1 前的文件哈希为：

```text
f58ac71baac0f8785c36b53271fbb436cff9912b
```

默认约束：

1. OrderCare 通过 `AgentExecutionProfile`、Capability Registry、Tool Runtime 和业务适配器接入。
2. 不在 `run()` 中加入 `if (ordercare)` 或 FlowOrder DTO。
3. 不新增第二套 Run 状态机取代 Runtime。
4. 若核心循环确有正确性缺陷，先留下失败测试和设计说明，再单独修改。

## 7. 分阶段学习地图

### M1：从 Agent 循环到真实业务 RPC

学习重点：

- `DefaultAgentRuntime` 如何发起模型轮次并接收 ToolCall。
- `DefaultAgentToolRuntime` 如何做 Schema 校验、调用和结果持久化。
- 强类型 HTTP Client、超时分类、统一响应体业务码。
- 防腐层为什么不能把 FlowOrder Entity 直接暴露给模型。
- Agent 如何动态诊断，但不能自行判断交易规则。

当前代码入口：

```text
ordercare/config/OrderCareExecutionProfileFactory
ordercare/application/OrderCareCaseInspector
ordercare/client/FlowOrderClient
ordercare/tool/OrderCareToolCatalog
ordercare/tool/OrderCareToolHandler
ordercare/model/OrderCareCaseSnapshot
```

### M2：HITL 与确定性副作用边界

学习重点：

- Proposal 与 Action Request 为什么必须是两个标识。
- 审批为什么绑定具体版本、指纹和影响摘要。
- Run 暂停后如何恢复原始 ToolCall，而不是让模型重新生成参数。
- Agent 工具幂等与 FlowOrder 业务幂等为什么不能互相替代。
- Java 收敛检查器为什么优于模型循环轮询。

当前代码入口：

```text
ordercare/application/OrderCareProposalBindingStore
ordercare/application/RecoveryConvergenceChecker
ordercare/tool/OrderCareApprovalRequestPreparer
ordercare/tool/OrderCareRecoveryToolHandler
ordercare/client/HttpFlowOrderClient
runtime/ApprovalToolCallRequestPreparer
runtime/DefaultAgentToolRuntime
```

M2 只在 `DefaultAgentToolRuntime` 增加审批前请求准备扩展点；`DefaultAgentRuntime.run()` 未加入 OrderCare 分支，也没有第二套恢复状态机。

### M3：生产型 Agent 可靠性

学习重点：

- 写请求超时后为什么不能换一个幂等键重试。
- `UNKNOWN`、`EXECUTING` 租约和 reconciliation。
- 进程崩溃、响应丢失和重复 resume 的状态恢复。
- Trace 如何串联 run、tool、approval、proposal 和 action。
- 不确定输出如何通过确定性断言和 LLM Judge 分层评估。

主要现有入口：

```text
runtime/JdbcAgentRuntimeStore
runtime/ToolExecutionState
trace/RuntimeTraceProjector
trace/JdbcTraceRecorder
eval/DefaultAgentEvalRunner
```

## 8. 分阶段中间件清单

| 工作 | MySQL | Redis | RabbitMQ | Nacos | PostgreSQL/pgvector | 模型 API |
|---|---:|---:|---:|---:|---:|---:|
| 阅读代码、纯单元测试 | 否 | 否 | 否 | 否 | 否 | 否 |
| M0.5 `Verify` | 是 | 否，测试中 mock | 否 | 否 | 否 | 否 |
| M0.5 `Scenario` | 是 | 是 | 是 | 是 | 否 | 否 |
| M1 Client/Tool 契约测试 | 否，HTTP stub | 否 | 否 | 否 | 按测试配置 | 否 |
| M1 真实只读联调 | 是 | FlowOrder 启动需要 | 非必需 | 可用固定 URL 代替 | 是 | mock 模式可不需要 |
| M1 真实模型 Eval | 否，固定 HTTP 契约 | 否 | 否 | 否 | 是 | 是 |
| M2/M3 完整业务演示 | 是 | 是 | 是 | 当前 FlowOrder 内部调用需要 | 是 | 是 |

当前不要求每次开发都启动全部中间件。只有真实跨服务验证时才启完整链路，普通编码优先使用窄测试和 HTTP stub。

## 9. M1 验收清单

- [x] FlowOrder 定义稳定的 Case DTO，不返回 Entity。
- [x] 支持四类业务标识并聚合预约、订单、扣减、库存不变量和关联死信。
- [x] 输出 7 类枚举诊断和服务端候选动作，不让 Agent 猜交易规则。
- [x] enterprise-agent 增加强类型 FlowOrder Client、有限重试和错误分类。
- [x] 增加 `ordercare-floworder-v1` 服务端 Profile。
- [x] 只暴露 `floworder_case_inspect` 与 SOP 检索能力。
- [x] 首批 8 条真实业务 Eval，真实模型 8/8。
- [x] 从现有统一 Run/SSE 执行线完成一次持久化只读诊断。

M1 只能表述为“实现异常订单智能诊断”，没有 Proposal、审批和 execute，不能宣称已经完成恢复闭环。

## 10. M2 验收清单

- [x] FlowOrder 是 Proposal 和 Recovery Action 的唯一权威事实源。
- [x] `proposalId` 与 `actionRequestId` 分离，并一对一绑定目标。
- [x] 审批绑定 Proposal 版本、状态指纹、影响/警告摘要、预演摘要和有效期。
- [x] 过期或状态漂移使原审批失效，执行前由 FlowOrder 重新校验。
- [x] 审批后恢复原始 ToolCall；模型只提交 `proposalId`，其余参数由服务端恢复。
- [x] execute 网络结果未知不自动重试写请求，并标记人工核对信息。
- [x] Java 有界轮询区分 `SUBMITTED` 和 `RESOLVED`，模型不负责循环回查。
- [x] 同一 Runtime 窗口展示案例、Proposal、审批、执行和收敛结果。
- [x] 真实 PostgreSQL Runtime E2E 与真实 MySQL/RabbitMQ 业务 E2E 均通过。
- [x] 10 条真实模型 Eval 通过率、工具匹配率和工具精确率均为 100%。

当前可以保守表述为“实现异常订单诊断与人工审批恢复闭环”。M3 未完成前，不能声称支持写响应丢失后的自动对账、执行租约接管或进程崩溃恢复。
