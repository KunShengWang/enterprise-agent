# OrderCare 实施状态与学习地图

> 更新时间：2026-07-18
> 当前阶段：M3 `PASSED`（Interview Strong）
> 下一阶段：M4 安全部署；不作为当前简历主闭环的完成前提

## 1. 文档用途

[项目总蓝图](enterprise-agent-master-blueprint.md)描述最终目标，本文件只记录已经被当前代码、测试或运行证据证明的事实。没有证据的能力一律视为未完成，不能写入简历。

## 2. 阶段看板

| 阶段 | 状态 | 已有证据 | 下一门禁 |
|---|---|---|---|
| M0 设计收口 | `PASSED` | 总蓝图 V1.1、Agent/确定性代码责任边界、分级 DoD | 保持设计与实现同步 |
| M0.5 FlowOrder Recovery Baseline | `PASSED` | 15 条测试、固定夹具、双扫描器 CAS、真实 RabbitMQ 跨服务恢复 | 开始 M1 只读契约 |
| M1 只读智能诊断 | `PASSED` | 稳定 Case 契约、7 类诊断、统一 SSE Run、8/8 真实模型 Eval | Proposal/Action 双标识与不可变预演 |
| M2 受控恢复闭环 | `PASSED` | 不可变 Proposal、版本审批、同 Run 恢复、领域幂等、确定性收敛、真实 RabbitMQ E2E、10/10 模型 Eval | M3 UNKNOWN 对账与崩溃恢复 |
| M3 故障正确性 | `PASSED` | 原 Action 对账、EXECUTING 租约、响应丢失、崩溃检查点恢复、重复 resume、20/20 真实模型 Eval、跨表 Trace 证据 | 保持故障证据可重复 |
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

## 6. M3 放行证据

M3 没有让模型决定重试写请求，而是增加一条确定性恢复支线：

```text
execute 返回 UNKNOWN / Runtime 恢复到 EXECUTING_TOOL
-> 查询原 actionRequestId
-> SUBMITTED/EXECUTING 时只对账，不创建新动作
-> 仅权威状态 NOT_STARTED 才按原审批参数补发一次
-> FlowOrder 按业务事实、死信状态和 Action 租约收敛
-> RESOLVED / NOT_CONVERGED / MANUAL_REVIEW
```

自动化与真实链路证据：

```text
enterprise-agent 默认测试：64 tests，0 failures，7 个外部 E2E 默认跳过
OrderCareM3ResponseLostRuntimeE2ETests：真实 PostgreSQL，响应丢失后只执行 1 次
OrderCareM3CrashRecoveryRuntimeE2ETests：真实 PostgreSQL，EXECUTING_TOOL 重启恢复且 0 次重复 execute
FlowOrder 恢复域测试：42 tests，0 failures
RecoveryProposalHttpE2ETest：真实 MySQL + Nacos + RabbitMQ，Action 查询/重复对账 1/1
Vue 生产构建：通过
```

2026-07-17 真实 DeepSeek M3 Eval：

```text
evalRunId              = 701dbbc3-e004-4cbb-b78a-74f1ce6e2050
passed                 = 20 / 20
passRate               = 1.000
averageScore           = 0.990
toolCallSuccessRate    = 1.000
toolPrecision          = 1.000
ragUsageAccuracy       = 1.000
groundednessRate       = 1.000
forbiddenViolationRate = 0.000
hallucinationRiskRate  = 0.000
adversarialPassRate    = 1.000
```

完整故障矩阵、真实关联 ID 和复现命令见 [M3 故障正确性报告](reports/ordercare/m3-fault-correctness.md)。

## 7. 核心学习主线保护

`DefaultAgentRuntime.run()` 是 Agent 学习主线，不承载 OrderCare 业务分支。开始 M1 前的文件哈希为：

```text
f58ac71baac0f8785c36b53271fbb436cff9912b
```

默认约束：

1. OrderCare 通过 `AgentExecutionProfile`、Capability Registry、Tool Runtime 和业务适配器接入。
2. 不在 `run()` 中加入 `if (ordercare)` 或 FlowOrder DTO。
3. 不新增第二套 Run 状态机取代 Runtime。
4. 若核心循环确有正确性缺陷，先留下失败测试和设计说明，再单独修改。

## 8. 分阶段学习地图

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

当前代码入口：

```text
ordercare/application/RecoveryOutcomeReconciler
ordercare/tool/OrderCareUncertainExecutionResolver
ordercare/client/HttpFlowOrderClient
runtime/UncertainToolExecutionResolver
runtime/DefaultAgentToolRuntime.reconcileUncertain
runtime/DefaultAgentRuntime.recoverExecutingTool
eval/OrderCareM3EvalSuite
```

`DefaultAgentRuntime.run()` 的完整模型循环仍保留；M3 只在已有 `recoverExecutingTool` 恢复点调用通用 `UncertainToolExecutionResolver`，没有加入 FlowOrder DTO 或 `if (ordercare)`。

## 9. 分阶段中间件清单

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

## 10. M1 验收清单

- [x] FlowOrder 定义稳定的 Case DTO，不返回 Entity。
- [x] 支持四类业务标识并聚合预约、订单、扣减、库存不变量和关联死信。
- [x] 输出 7 类枚举诊断和服务端候选动作，不让 Agent 猜交易规则。
- [x] enterprise-agent 增加强类型 FlowOrder Client、有限重试和错误分类。
- [x] 增加 `ordercare-floworder-v1` 服务端 Profile。
- [x] 只暴露 `floworder_case_inspect` 与 SOP 检索能力。
- [x] 首批 8 条真实业务 Eval，真实模型 8/8。
- [x] 从现有统一 Run/SSE 执行线完成一次持久化只读诊断。

M1 只能表述为“实现异常订单智能诊断”，没有 Proposal、审批和 execute，不能宣称已经完成恢复闭环。

## 11. M2 验收清单

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

## 12. M3 验收清单

- [x] execute 传输异常不进入通用写重试，返回 UNKNOWN 并查询原 Action。
- [x] 只在 Action 明确为 NOT_STARTED 时，使用原 ToolCall、审批参数和 actionRequestId 补发一次。
- [x] FlowOrder 使用 executionOwner、executionLeaseUntil 和 CAS 接管过期 EXECUTING 动作。
- [x] 业务已收敛但 Action 日志未完成时补记 SUBMITTED；无法证明时进入 MANUAL_REVIEW。
- [x] Runtime 从持久化 EXECUTING_TOOL 检查点恢复，并补写匹配的 ToolResult 时间线。
- [x] 响应丢失、崩溃恢复和重复 resume 均有真实 PostgreSQL 自动化证据。
- [x] 20 条真实模型 Eval 达到 20/20，工具精确率与 groundedness 均为 100%。
- [x] 工作台展示 responseLost、reconciled、reconciliation attempts 和 recoveredAfterCrash。

当前可以表述为“支持副作用结果未知、进程崩溃和重复恢复场景下的幂等对账与故障恢复”。M4 未完成前，仍不能宣称生产级安全、线上规模或完整 SLO。

## 13. Incident Command Phase 1（独立事故级 Multi-Agent 场景）

| 阶段 | 状态 | 证据 |
|---|---|---|
| M0 设计冻结 | `PASSED` | Incident Command V1.3，Evidence 双维度、Run 拓扑、统计口径、事务和 Fixture 契约 |
| M1-A 只读事实层 | `PASSED` | FlowOrder 订单/库存/死信接口和 dead-letter-first MQ 复合能力 |
| M1-B 四表与事务 | `PASSED` | 7 条真实 PostgreSQL 事务/CAS/幂等测试 |
| M1-C 同 Run 续跑门禁 | `PASSED` | 3 条真实 PostgreSQL 重启、CAS、终态测试 |
| M1-D 调查闭环 | `PASSED` | Commander、3 Specialist、Evidence、Conflict、Reviewer、Assessment、Trace |
| M1-E 证据与演示 | `PASSED` | 3 条纵向 E2E、10 条核心 Eval、真实 MySQL/Rabbit Fixture、单窗口页面 |

权威证据：[Incident Command Phase 1 报告](reports/ordercare/incident-command-phase1-evidence.md)。该场景只读，不包含 Recovery Planner、批量恢复、多实例 Task lease 或通用 Agent Mailbox。

## 14. Incident Command Phase 2（Recovery Planner）

| 能力 | 状态 | 当前事实 |
|---|---|---|
| 强类型 Recovery Plan | `IMPLEMENTED` | 独立 Planner Run；最多 5 个 ProposalRequest；无工具和写权限 |
| Java 安全校验 | `PASSED` | 约束 Incident/Assessment、风险、EvidenceGap、OPEN HIGH conflict、scopeHash、requestId 范围、动作白名单和证据引用 |
| FlowOrder Proposal | `IMPLEMENTED` | 逐项复用现有单 Proposal API；稳定 proposalId；无批量写接口 |
| HITL | `IMPLEMENTED` | 每项独立 Approval，绑定 plan/item/assessmentDigest/previewDigest，TTL 和决定 CAS 复用统一 ApprovalService |
| 执行与收敛 | `IMPLEMENTED` | Recovery Plan version CAS claim、原 actionRequestId、UNKNOWN 对账、ConvergenceChecker、逐项结果汇总 |
| Trace/UI | `IMPLEMENTED` | Planner Run 进入 Incident Trace；单窗口展示 Proposal 影响、警告、证据引用和逐项审批 |
| 安全 Eval | `PASSED` | 合法单项/多项通过；越权、扩范围、虚构 Evidence、重复目标、超预算等 fail closed |
| 全仓与前端回归 | `PASSED` | Maven 全仓测试与 Vue 生产构建通过 |
| 真实 PostgreSQL CAS | `PASSED` | 真实 PostgreSQL 上验证 requestKey 幂等、version CAS 与旧版本冲突拒绝：1/1 通过 |
| 完整 Runtime E2E | `PASSED` | 真实 Runtime/PG + 确定性 FlowOrder/模型 Stub：Phase 1 三场景与 Phase 2 恢复闭环共 4/4 通过 |

Phase 2 不把 Incident 从 `ASSESSED` 重新打开；Recovery Plan 是独立聚合。Planner Run 完成后释放模型资源，人工等待发生在 Recovery Plan/Approval 上。当前仍不包含 Phase 3 的多实例 lease、stale execution 回收和进程崩溃接管。

权威证据：[Incident Command Phase 2 报告](reports/ordercare/incident-command-phase2-evidence.md)。
