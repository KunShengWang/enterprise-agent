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

合同测试只通过 classpath 资源读取 JSON，并使用 `AwsSyntheticProcurementProvider` + `ProcurementDecisionEngine` 复算 Eligibility；不实例化 Agent Runtime、不调用 LLM、不引入 Eval Runner 或 LLM judge。Live Model 的实际执行路径见下方 Phase 6C：Live model → existing Agent Runtime → frozen Benchmark v1 → deterministic structured grader。

## 9. Phase 6C Opt-in Live Model Evaluation

Phase 6C 在 Phase 6B 冻结的 `procurement-benchmark-v1` 上做一次单模型、单次 rollout 的 Live Benchmark v1 observation。它只复用现有 `NativeToolCallingAgentModelGateway`、`DefaultAgentRuntime`、`AwsSyntheticProcurementProvider` 和测试专用内存 Store；不修改生产代码、Benchmark JSON、通用 Eval Framework 或 POM。

Live 测试类是 `ProcurementLiveModelEvalIT`，默认 `mvn test` 不选择 `*IT`。即使显式选择该类，也必须先通过 `PROCUREMENT_LIVE_EVAL=true` 这一独立 opt-in gate；Gate 不通过时直接 skip，且在 Gate 之前不创建 Spring Context、Gateway、Provider 或模型请求。Gate 通过后才读取 `DEEPSEEK_API_KEY`；已 opt-in 但 Key 缺失时测试失败，不自动 skip、mock 或 fallback。模型名沿用 `DEEPSEEK_CHAT_MODEL`，未配置时为 `deepseek-chat`。

PowerShell 示例（Key 只注入当前进程，不写入仓库）：

```powershell
$env:DEEPSEEK_API_KEY = '<安全注入当前终端的 Key>'
$env:PROCUREMENT_LIVE_EVAL = 'true'
mvn -q -Dtest=ProcurementLiveModelEvalIT test
```

Live Profile 移除 `procurement_create_rfq`，因此只允许对外部世界只读；`procurement_case_patch` 仅写入当前 Case 的隔离内存 Store。Commercial/Delivery Specialist 保持为可选真实 capability：模型可以调用 0 个或 2 个，若调用由既有 Runtime 保证同轮 native parallel。四个 Case 各自使用独立 conversation、CaseStore、RunStore、TimelineStore 和 ToolExecutionStore。

Grader 只读取 CaseStore 的最终 `ProcurementCaseState`、成功的 Supplier Search ToolResult、成功的 Finalize ToolResult 及其中的 Provider Evidence。Requirement Extraction、Eligibility、Recommendation Outcome 和适用 Case 的 Evidence Grounding 都是结构化精确比较；不读取最终中文文案，不使用关键词、语义相似度、LLM-as-a-Judge 或加权总分。NO_ELIGIBLE Case 不要求 Evidence Grounding，但必须没有成功 Recommendation。

报告只写入 `target/procurement-live-eval/report.json`，这是本地 ignored artifact，不是提交的 Benchmark 结果。报告包含 Parent 与 provenance-backed Child Run 的 model calls、input/output tokens 和 child runs；不会保存 API Key、请求头、完整 Prompt 或凭证。四个 Case、一次 rollout 不具备统计意义，只能称为 Live Benchmark v1 observation，不能称为 production accuracy、真实准确率或 ROI。

## 10. 如何运行

```text
mvn -q -Dtest=ProcurementAgentRuntimeE2ETests test
mvn -q -Dtest=ProcurementRfqHitlTests test
mvn -q clean test
git diff --check
```

最终报告使用 Maven 原始统计：`Tests run`、`Failures`、`Errors`、`Skipped`。PostgreSQL CAS IT 只有在配置 `PROCUREMENT_POSTGRES_IT=true` 时才运行；未配置时必须报告 `NOT RUN / environment not configured`。

## 11. Phase 1 Java Artifact Evaluation（离线最小闭环）

新增基础设施均位于 `src/test/java/com/agent/platform/procurement`，不进入生产 API 或 JDBC：

| 文件 | 职责 |
| --- | --- |
| `ProcurementEvaluation` | `procurement-evaluation-v2` 契约：Case、ExecutionArtifact、Check、Result、Report、Comparison |
| `ProcurementEvaluationDataset` | 只读加载冻结 Benchmark v1，记录原始文件 SHA-256，不从 Agent/Finalizer 生成答案 |
| `ProcurementExecutionArtifacts` | 从已执行 Runtime result、CaseStore、ToolExecutionStore、Timeline 的快照构造 Artifact；不启动执行 |
| `ProcurementDeterministicGrader` | `procurement-deterministic-v2` 纯离线评分，不依赖 Live IT、Provider 或模型 |
| `ProcurementEvaluationReports` | JSON 导出/读取、Artifact 指纹、批量重评分、状态计数及报告比较 |
| `ProcurementDeterministicGraderTests` | 手工正反例及契约/重放/比较测试 |
| `ProcurementArtifactReplayTests` | 通过显式文件参数对已有 Artifact 重评分，默认跳过 |
| `ProcurementAgentRuntimeE2ETests` | 复用已有 scripted Runtime 的单个冻结用例，导出实际执行证据并验证重评不再执行 |

### 执行与评分边界

流程为 `EvaluationCase → 已有 Runtime 执行 / Existing Artifact → ExecutionArtifact → Grader → Result → JSON Report`。
Grader 不持有 Runtime、模型或业务 Gateway；重评只需要 Case 和 Artifact。
本轮保留已有未提交 `ProcurementLiveModelEvalIT` 原样，旧入口及旧评分暂不迁移。新闭环没有调用旧 IT 的评分方法。
`fromLiveExecution` 是读取已有 Harness CaseExecution 的适配器，不会调用 `start()` 或 `run()`；本轮未进行 Live 验证。

Artifact 复用 `AgentRuntimeResult`（含 answer、approvalId、parent budget）、`ProcurementCase`、`ToolExecutionRecord`、`AgentEvent`。
工具结果 content 保留原始 JSON，其中包含 evidenceId/source/sourceRecordId/sourceSnapshot/sourceAsOf/sourceDigest 等来源字段。
不将旧 Live `report.json` 转换成完整 Artifact：旧报告缺少原始回答/工具证据，不能补造。

| 观察 | 保存位置与解释 |
| --- | --- |
| 模型提出请求 | supplied timeline 中 `TOOL_REQUESTED` payload（包含 arguments、toolCallId） |
| 策略决定 | supplied timeline 中 `POLICY_DECIDED`；不保证所有拒绝路径都有该事件，不从事件缺失推断允许 |
| 工具实际执行 | `toolExecutions` 中原始 request/result/state/attempt；只有 SUCCEEDED 且 result.success 的记录参与成功结果评分 |
| 业务规则失败 | 原始失败 ToolExecutionRecord/result 保留；不把所有失败自动分类为 Guardrail 拒绝 |
| 最终业务状态 | `finalCase`；不虚构中间状态或外部系统状态 |
| 审批 | 原始 approvalId 和实际观察到的事件；尚无独立审批决策台账 |
| 副作用 | 没有采集外部回执/Gateway 计数，因此外部副作用指标 SKIP；不能把工具成功当作真实创建证明 |

`toolExecutions=null` 表示缺失采集，空数组表示已采集但没有记录。必需采集缺失产生 ERROR。
metadata 预留 executionMode/codeRevision/model/promptSha256/toolSchemaSha256/modelConfigSha256/fixtureSha256；未提供值为 UNKNOWN。fixtureSha256 必须与 Case 的实际 fixture 文件哈希一致，否则评分 ERROR；其他 UNKNOWN 不影响结构化检查，但不能宣传完整执行复现。
E2E 会提供真实 fixture 文件哈希；codeRevision 可显式注入（未注入为 UNKNOWN）。Git SHA 加 working-tree 标记不是工作区内容指纹。
当前只保存父 Run budget，不汇总子 Run 消耗；事件仅保留调用方传入的范围。Live 适配器读取最多 10000 个父 Run 事件，完整轨迹合规仍为 SKIP。
原始 Artifact 可能包含业务数据；当前演示仅使用合成 fixture，本阶段不提供生产数据脱敏器。

### 评分范围与状态

- 沿用现有工作区 Requirement Grader v2 的口径：产品类别/描述非空；数量、预算、币种、交期、硬约束、偏好、排除集合精确比较。文本逐字匹配仅记录诊断，产品语义为 SKIP。
- 保留恰好一次成功 Search、eligible 集合精确匹配、推荐场景恰好一次成功 Finalize、推荐与 selectedOffer 的 supplierId 匹配。没有放宽重试/多轮次数限制。
- NO_ELIGIBLE 禁止成功 Finalize；推荐证据和 tradeoff 指标为 NOT_APPLICABLE。
- 校验冻结 requiredTradeoffDimensions；推荐证据必须同时解析到 Search/Finalize，来源与事实字段一致，SupplierEvidence ID 通过既有来源绑定校验，并包含选定供应商 OFFER 与所需类型。
- 证据一致性不是外部来源真实性证明。报价金额、交期与 provider fixture 的独立事实比对尚未实现，offerFactCorrectness 为 SKIP；不能因 supplierId 正确而推断这些事实正确。
- 最终回答原样保存，finalAnswerCorrectness/finalAnswerFaithfulness 始终 SKIP；即使回答虚构“已创建 RFQ”，也不会被伪装成已评测通过。
- 工具参数/偏序、完整 HITL、权限、副作用和性能阈值仍为 SKIP；已有专用业务测试继续承担这些机制的离线回归。

PASS/FAIL 是已执行规则的符合/违反；ERROR 是身份/数据不匹配、缺失或损坏证据等不可可靠评分；SKIP 是尚未评测；NOT_APPLICABLE 是当前用例定义不适用。
Result 的 `structuredStatus` 仅汇总已实施结构化规则（ERROR 优先于 FAIL），不是任务成功率或最终回答准确率。Report.scope 明确这一限制。
每项 Check 包含 reason 和 Artifact JSON pointer。Report.checkCounts 按五种状态统计检查项，不把 SKIP/NOT_APPLICABLE 算作通过。
单 case 评分异常保留 ERROR 并继续其他 case；缺少 Artifact 也产生 ERROR。重复或混合 Dataset 等批次契约错误直接拒绝。

### 离线运行和重评分

```powershell
# 生成真实 scripted Runtime 证据，不调用外部模型或业务服务
mvn -q '-Dtest=ProcurementAgentRuntimeE2ETests#exportsFrozenCaseExecutionAndRegradesWithoutRunningAgentAgain' test

# 只读取上一步 Artifact；不会再次启动 Agent，也不会执行业务工具
mvn -q '-Dtest=ProcurementArtifactReplayTests' '-Devaluation.artifact=target/procurement-evaluation/runtime-artifact.json' test

# 手工正反例与 JSON/比较契约验证
mvn -q '-Dtest=ProcurementDeterministicGraderTests' test
```

输出均在 ignored 的 `target/procurement-evaluation/`：`runtime-artifact.json`、`runtime-report.json`、`handcrafted-report.json`、`replay-report.json`。
这些固定路径是本地演示产物，再运行会覆盖；需要保留实验时由调用者传入独立目录，Replay 可用 `-Devaluation.report=<path>` 指定输出。
Replay 即使评分 FAIL/ERROR 也先写报告，再使 JUnit 失败；默认没有 evaluation.artifact 时测试 SKIP。

Artifact SHA-256 使用对象属性排序，并归一化 Case 中两个 Set 字段的顺序，其余数组（包括事件）顺序保留；哈希是完整性指纹，不是签名/来源认证。
相同 Artifact 重复评分应产生相同结果。JSON round-trip 及 E2E 中的模型/工具计数用于验证这一点。

### 版本比较

- 未知 schema 的报告拒绝处理；当前 schema 下 Dataset/fixture/报告 scope 或用例覆盖不同：INCOMPARABLE。
- 同 Grader、同 Artifact：REPLAY_CHECK；若逐项状态反而变化，标为 INCONSISTENT_REPLAY。
- 同 Artifact、不同 Grader：GRADER_CHANGE_ON_SAME_ARTIFACTS，只解释评分规则变化。
- Grader 和 Artifact 都变化：INCOMPARABLE。
- 同 Grader、不同 Artifact：列出逐用例逐指标状态变化，标记 ARTIFACT_CHANGE_NOT_CAUSAL_MODEL_CLAIM。尚未做模型、Prompt、配置等混杂因素控制，不能称为模型提升。

Ground Truth 继续由冻结 Benchmark 及其独立合同测试管理。手工构造证据是 Grader 的单元测试，不是模型准确率数据；scripted E2E 也不证明真实模型能力。

### 最终复核修正（schema / grader v2）

本次仅修复评分基础设施可靠性，不增加评测维度。旧 schema v1 Artifact 明确拒绝，不静默补字段迁移；新增 fixture 契约和收紧评分检查以新版本区分，旧报告不得直接声称质量回归。

- Case 与 Artifact 校验 caseId/dataset SHA/输入/fixture SHA；捕获时记录业务 Case ID、tenant/user，评分时核对。ToolExecutionRecord、父 Run 事件均核对 run/session 身份。Search 仅提供 caseVersion，按实际字段验证；Finalize 同时验证 caseId/caseVersion。证据快照和 sourceAsOf 必须符合冻结 fixture。
- 成功工具缺失 result、success 状态矛盾、COMPLETED Run 包含 RUNNING 工具、缺失/空身份、运行终态矛盾均为 ERROR。空/重复数组项、单候选缺失 tradeoffDimensions 不再以默认空集合放过。
- 原始 JSON 在转换成生产 record 前校验必要字段。缺失 createdAt/updatedAt 不得被补成当前时间；缺失 currency、工具 state、success、回答等字段不得经默认值掩盖。未知 enum/非法数值类型拒绝读取并报 IOException。文件读取失败与已加载 Artifact 的业务 FAIL 分开。
- Comparator 先核对逐项状态、structuredStatus、报告计数和 Grader 标识的一致性。空报告、伪造 PASS 或计数拒绝；缺少 Artifact 指纹不可比较。SKIP/NOT_APPLICABLE 不计为 PASS，不生成混合“成功率”。
- 指纹是完整 Artifact 内容哈希，不是业务等价哈希：证据中的真实时间戳、模型/运行元数据发生变化时指纹应变；重评分不注入当前时间或输出绝对路径。移动同一文件、对象属性及已声明 Set 顺序变化不影响重评。工具 content 字符串、事件顺序仍保留原样，不把不同执行伪装成同一证据。
- 身份和哈希验证用于检测不一致或误混用，不是加密签名。无法证明同时伪造所有字段的 Artifact 来自真实执行；生产数据、完整多租户认证链仍未纳入此测试侧闭环。
