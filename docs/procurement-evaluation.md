# Procurement Evaluation Baseline

## 1. 评测目标

Phase 6A 为已经实现的 Procurement Agent 建立可复现的确定性基线，观察业务结果、Adaptive delegation trajectory、资源开销，以及 RFQ approval/resume 的副作用安全性。本阶段是验证层，不增加 Runtime、Metrics 或业务能力。

## 2. Deterministic vs Live Evaluation

测试使用现有 scripted model、synthetic provider 和 fake MCP fixture。相同场景的 ablation 只切换是否启用 Commercial/Delivery Specialist；Case facts、供应商事实、预算、交期、数量和决策策略保持不变。因此结果可以证明执行轨迹和资源开销基线，不能证明真实模型或供应商质量提升。

## 3. Scenario Matrix

| 场景 | 预期业务结果 | 关键执行指标 |
| --- | --- | --- |
| A. Complex / Specialist OFF | canonical supplier D | child Run=0 |
| B. 同一 Complex / Specialist ON | canonical supplier D | child Run=2；Commercial 与 Delivery native parallel |
| C. Simple / one eligible | canonical supplier D | child Run=0；不执行 Specialist |
| D. Explicit RFQ + approval | `WAITING_APPROVAL` 后同一 Run 完成 | approval 前 create=0；resumeCount=1；create=1；Tool attempt=1 |
| E. RFQ external state unresolved | `MANUAL_REVIEW` | max attempts=3；create=1；不盲目重放 create |

## 4. Multi-Agent Ablation

Complex 场景的 OFF/ON 两次执行都必须从成功的 `procurement_recommendation_finalize` 得到 Supplier D。OFF 路径没有子 Agent、Commercial 或 Delivery tool execution；ON 路径包含两个 distinct child Run 和两个 distinct child Session，并通过同一 parallel batch 调用两个 Specialist。

ON 的 total model calls 以及 total input/output tokens 应高于 OFF。这里的差异表示 Specialist decomposition 的额外执行开销，不能解释为准确率、性价比或 ROI 提升。

Parent 与 descendant usage 分开统计：Parent 来自 `AgentRuntimeResult.budget`；child Run ID 来自 Parent Timeline 的 `SUB_AGENT_COMPLETED.childRunId`，再通过 `AgentRunStore` 读取 child budget。缺失 child Run 或 budget 不会按零值处理，而会使评测失败。

## 5. Resume / Side-effect Safety

审批等待状态直接读取 `AgentRunRecord.resumeCount`，应为 0 且 Gateway create 次数为 0。批准后 resume 必须保持相同 `runId`，`resumeCount=1`，RFQ `ToolExecutionRecord.attempt=1`，Gateway create 次数为 1。重复 resume 不得再 create。

外部状态未知时，即使 Runtime 允许最多 3 次工具尝试，也必须保持同一 Run 并进入 `MANUAL_REVIEW`；真实 RFQ ToolExecution 必须为 `MANUAL_REVIEW`、attempt=1，Gateway create=1、find=1，不得把未知状态当作普通可重试失败。

## 6. Metric Sources

- Outcome：成功的 `procurement_recommendation_finalize` `ToolExecutionRecord.result` 中的 canonical recommendation，不从展示文本推断。
- Trajectory：Parent `AgentEvent` Timeline，包括 `SUB_AGENT_STARTED`、`SUB_AGENT_COMPLETED` 和带 `parallelBatch` 的 `TOOL_REQUESTED`。
- Parent usage：`AgentRuntimeResult.budget`。
- Child usage：`SUB_AGENT_COMPLETED.childRunId` → `AgentRunStore` → child budget snapshot。
- Resume：`AgentRunRecord.resumeCount`。
- Side-effect attempt：`ToolExecutionRecord.attempt` 与测试 Gateway 的 create/find 计数。

Runtime 的 `estimatedCost` 会被保留，但 scripted model 和测试定价只适合作为运行记录，不作为本阶段的正确性门槛。

## 7. Claim Boundaries

本阶段可以证明：

- deterministic business regression；
- adaptive routing 与 Specialist execution overhead；
- child Run isolation 与 usage provenance；
- approval/resume 的 same-Run 行为；
- RFQ 未知外部状态下不盲目重放。

本阶段不能证明：

- production accuracy、真实供应商质量或 live model recommendation uplift；
- 真实 ERP latency、production exactly-once 或 Multi-Agent ROI；
- 统计显著的质量或性能改善。

未来 Phase 6B 才可在真实模型、多案例 ground truth 和明确评测协议基础上研究质量指标；本阶段不实现 Phase 6B。

## 8. 如何运行

```text
mvn -q -Dtest=ProcurementAgentRuntimeE2ETests test
mvn -q -Dtest=ProcurementRfqHitlTests test
mvn -q clean test
git diff --check
```

最终报告使用 Maven 原始统计：`Tests run`、`Failures`、`Errors`、`Skipped`。PostgreSQL CAS IT 只有在配置 `PROCUREMENT_POSTGRES_IT=true` 时才运行；未配置时必须报告 `NOT RUN / environment not configured`。
