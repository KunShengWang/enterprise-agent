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

Phase 6B 当前冻结离线 benchmark-v1 的数据契约与 Ground Truth；它不运行真实模型，也不声明模型准确率。未来 Phase 6C 才可在该冻结数据集上，以 opt-in 方式研究 live model evaluation。

## 8. Phase 6B Benchmark v1

`procurement-benchmark-v1` 在任何 live model rollout 之前冻结，文件位于 `src/test/resources/procurement/benchmark/procurement-benchmark-v1.json`，只引用 `complex_workstation_01.json`，不复制 Provider facts。它是 Benchmark Definition & Validation，不是准确率报告。

四层契约分别是：`userMessage` 代表真实自然语言输入；`expectedCase` 代表 requirement extraction ground truth；`eligibleSupplierIds` 来自 Provider facts 与 Java deterministic eligibility；`preferredSupplierId` 来自明确的 delivery/price preference rubric，不是 Java recommendation engine。四个 case 覆盖双候选交付优先、双候选价格优先、单一 eligible 和无 eligible；后两者分别要求不虚构 trade-off、不得从不合格供应商中强行推荐。

`preferredSupplierId` 由冻结的用户偏好与 Provider facts 独立策展，不能由 Agent、`ProcurementRecommendationFinalizer` 或之前一次模型运行结果自动生成，否则被测系统会参与生成自己的答案。Benchmark 不保存 `unitPrice`、`totalPrice`、`leadTimeDays`、`warranty`、规格或 evidence ID；这些继续属于 Provider fixture 与 `ProcurementDataProvider`。本阶段不包含 RFQ、approval、risk/compliance、token、child agent 或自然语言标准答案。

合同测试只通过 classpath 资源读取 JSON，并使用 `AwsSyntheticProcurementProvider` + `ProcurementDecisionEngine` 复算 Eligibility；不实例化 Agent Runtime、不调用 LLM、不引入 Eval Runner 或 LLM judge。未来 Phase 6C 的概念路径是：Live model → existing Agent Runtime → frozen Benchmark v1 → deterministic structured grader，本阶段不实现。

## 9. 如何运行

```text
mvn -q -Dtest=ProcurementAgentRuntimeE2ETests test
mvn -q -Dtest=ProcurementRfqHitlTests test
mvn -q clean test
git diff --check
```

最终报告使用 Maven 原始统计：`Tests run`、`Failures`、`Errors`、`Skipped`。PostgreSQL CAS IT 只有在配置 `PROCUREMENT_POSTGRES_IT=true` 时才运行；未配置时必须报告 `NOT RUN / environment not configured`。
