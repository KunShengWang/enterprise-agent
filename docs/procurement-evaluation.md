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

### Phase 7B-1：独立回答事实 sidecar

本节新增的测试侧闭环不修改以上 Phase 7A schema v2、Grader v2、聚合规则或历史 Artifact。
`ProcurementAnswerGrader` 读取已保存回答，生成 `procurement-answer-v1` sidecar；提取器为
`procurement-claims-v1.1`，评分器为 `procurement-answer-grader-v1.1`。没有模型、Agent、工具执行器依赖。

流程：`readArtifact → 7A 身份校验 → 独立 ReferenceFacts / ObservedEvidence → AnswerClaimExtractor → 逐主张双维度评分 → write(sidecar)`。
7A 的 structuredStatus 原样保留；最终回答错误不会反向修改结构化报告。

- ReferenceFacts 核对冻结 Benchmark 字节哈希及 case 的实际 expected/expectedCase/input/providerFixture，核对输入 Fixture 字节与 case/artifact fixtureSha256 及 scenario/sourceAsOf。缺失、错版或不匹配不回退其他 Fixture。单价、币种、报价交期来自 Fixture；总价用 Fixture 单价乘冻结数量，以 BigDecimal 计算；推荐及备选资格来自 Benchmark。
- ObservedEvidence 独立读取本次成功工具的原始 JSON、最终 Case 数量和要求期限。报价必须具有匹配的 supplierId/productId；Search 与 Finalize 的多份证据若互相冲突，不取第一份放行。不从工具请求、拒绝或失败记录推导成功事实。
- 工具报价始终保留原始 totalPrice，不反序列化为会重新计算总价的 SupplierOffer。小数采用 BigDecimal；工具与答案相同但与 Fixture 矛盾时，事实 FAIL，支持判定标为 `CAPTURED_MATCH_CONFLICTS_WITH_REFERENCE`，不当作可信支持。
- Faithfulness 的范围是“已捕获执行证据是否支持该主张”，不是已证明模型实际看到了完整原始工具结果；生产 ToolResultProjector 可能裁剪模型上下文。

AnswerClaim 保存原文、Java UTF-16 字符区间 `[start,end)`、主体、属性、BigDecimal 数值、单位/币种、语气、提取器版本及未决原因。
逐主张分别输出 factualCorrectness 和 faithfulness 的 status/reason/evidencePaths，路径以 `fixture:`、`benchmark:`、`artifact:` 区分来源。
sidecar 关联 caseId/datasetSha256/artifactSha256/fixtureSha256 和两种规则版本，不注入重评时间戳、输出路径。

支持的有限表达：

| 类型 | 示例与限制 |
| --- | --- |
| 推荐 / 备选 | `推荐 Supplier D`、`推荐供应商 Supplier D`、`备选 Supplier B`；备选事实正确表示冻结 eligible 集合中非首选成员，执行支持仍须 Finalize 中明确列出 |
| 单价 / 总价 | `总价 580000 元`、`总价 58 万元`、`单价为 11600 CNY`；支持阿拉伯数字、十进制小数、万倍率 |
| 币种 | 元/人民币/CNY、美元/USD、欧元/EUR，及 `币种 USD`；scripted 无币种金额显式记为 CASE_CURRENCY，按冻结 case 币种解析，仍须与报价币种吻合 |
| 数量 / 期限 | `采购数量 50 台`、`数量 50 台`、`采购 50 台`、`用户要求期限 21 天`；与供应商 `交期 12 天`、`报价交期 12 天` 分开 |
| 状态识别 | `本阶段仅完成只读推荐`、`待审批`、`RFQ 已创建`、`RFQ 创建成功`、`执行失败`；现阶段识别状态主张但不裁定外部事件真实性，双维度 SKIP 并记录缺少权威事件真值/充分状态证据 |

供应商仅在逗号连接的明确语句内继承；句号、分号、未知子句清除主体。否定、条件、将来/过去、模糊语气及问句保留原文并标为未决；条件作用于整个逗号连接句，避免把条件结果识别为已执行。
所有无法完整消费的非标点文本都会形成未决项，不静默跳过。中文数字、复杂表格、代词消解、多商品、混合币种换算、比较理由、开放语义和完整性不支持。

状态和报告口径：

- 逐主张 PASS：该维度有足够匹配依据；FAIL：与明确事实或捕获证据矛盾。
- SKIP + reason：无法解析、主体未决、证据不足、状态缺少权威真值等；不等于事实为假，也不等于通过。
- ERROR：Artifact 身份、Fixture/Benchmark 错配、原始数字损坏等基础设施问题。证据索引损坏时，独立事实判定仍可保留，证据判定 ERROR。
- completeAnswerStatus 始终 SKIP。evaluationStatus 是局部诊断汇总：ERROR 优先，其次 FAIL，否则 SKIP；即使所有可识别主张通过，也没有整篇 PASS 或成功率。未实现的完整性不能由非空主张集合替代。7A 中原有最终回答指标仍然 SKIP，应另读 sidecar。
- 无 RFQ 成功记录不是“RFQ 绝未发生”的证明。Run COMPLETED、Finalize 成功、approvalId 和模拟 RFQ 成功记录也不是外部创建凭证。本阶段状态表述的证据不足会被显式暴露，不判定完整 HITL、权限或副作用。

离线运行：

```powershell
# 端到端手工正反例，包含同一 Artifact 两次磁盘重评与输入不变检查
mvn -o '-Dtest=ProcurementAnswerGraderTests' test

# 读取已有 scripted Artifact；不生成新执行，不调用模型或业务工具
mvn -o '-Dtest=ProcurementAnswerReplayTests' '-Danswer.artifact=target/procurement-evaluation/runtime-artifact.json' test
# 可额外指定 -Danswer.fixture=<匹配原始哈希的文件> -Danswer.report=<独立输出文件>
```

未指定 answer.artifact 时显式 Replay 测试跳过；输入文件无法读取时抛出 IOException，不报告通过。已加载输入的评分错误写入 sidecar。
Replay 遇到 FAIL/ERROR 先保存报告再使测试失败，SKIP 是预期的局部覆盖结果；它不证明最终回答完整正确。
输出默认 `target/procurement-evaluation/answer-sidecar.json`，手工重评样例为 `answer-replay-report.json`。
禁止 sidecar 覆盖 Artifact 或 Fixture（包含同文件链接检查）；两次重评 JSON 字节必须相同。规则升级与比较留待后续阶段，现有 7A Comparator 不接受此独立 sidecar。

Phase 7B-1 最终复核修正（规则 v1.1，sidecar schema v1 不变）：

- 不再从不支持的主体语句截取一个推荐前缀并判定通过；整段原文转为未决。显式条件的跨句作用域不做推理，出现条件标记时整个回答保守未决。疑问和否定限定同样不转为确定事实。Emoji 和补充平面字符使用原始 UTF-16 区间，不做字符串规范化或截断代理对。
- 显式供应商主体的数量或用户要求期限不再静默改绑为 Case 主体；这种归属歧义保留 supplier 主体并标为未决。
- 报价支持必须具有匹配冻结快照时间及 snapshot 的来源字段，且能关联同工具 payload 内、同供应商/sourceRecordId 的 OFFER 证据；校验其内容派生 evidenceId 以及 source/digest 等来源字段。来源缺失不计支持，过期或冲突来源产生 ERROR；来源哈希不是防伪签名。
- 总价支持必须具有匹配采购数量；原始单价、数量、总价互相矛盾时为 ERROR，保留原始值而不修正。内部数值一致、但与冻结事实矛盾的工具断言仍为事实 FAIL，不反向成为 Ground Truth。
- 无法通过 7A evidenceGrounding 的推荐结果不提供推荐/备选支持；多个成功 Finalize 的类别结论视为歧义证据。事实判定、证据判定和回答摘要分开保留，摘要 ERROR 不抹除逐主张 FAIL，完整回答仍为 SKIP。
- 修复改变提取和评分结果，因此只提升独立规则版本；历史 Artifact、Phase 7A schema v2 和 Grader v2 均不迁移、不修改。旧规则报告与 v1.1 报告不能直接解释成模型能力变化。

### Phase 7B-2A：回答覆盖与独立 Answer Policy

本批次仅增加覆盖记账和必答元素定义，不实现完整性、比较关系或推荐理由评分，不开放完整回答 PASS。
原 `ProcurementAnswerClaimExtractor` v1.1、`ProcurementAnswerGrader` v1.1、sidecar v1 和旧重评入口均保持原样。

执行流程：历史 Artifact → 复用旧身份/事实校验与逐主张评分 → 验证 Policy 与冻结数据绑定 → 调用原 Extractor 分析完整回答 → 覆盖片段与规则定义 → 独立 `procurement-answer-v2` sidecar。

覆盖由 `ProcurementAnswerCoverage.analyze(answer)` 提供：

| 分类 | 判定边界 |
| --- | --- |
| PARSED_CLAIM | 原提取器已可靠解析该片段中的主张；仅表示解析成功，不表示事实、证据支持或业务执行成功 |
| UNRESOLVED_BUSINESS | 主体/语气等未决，或明确涉及供应商、金额、交期、状态、承诺等业务内容但无法解析 |
| NON_FACTUAL | 完整片段严格匹配有限白名单，或纯分隔符/空白；绝不使用 contains 抹除文字 |
| UNKNOWN_CONTENT | 既未可靠解析，也无法证明是非事实文本；默认阻断未来完整回答 PASS |

白名单仅为 `谢谢`、`谢谢您`、`感谢您的帮助`、`您好`、`你好`、`以下是建议`；仅忽略片段首尾空白用于匹配，输出仍保留原文。
例如“谢谢，保证绝不延期”会保留后半句；“谢谢供应商保证交付”整段不能进入白名单。业务词提示仅用于区分未决业务与未知文本，不用于将文本判为无害。
先对完整回答调用旧 Extractor，再分段关联结果，不先删礼貌语或重排回答；因此礼貌语介入导致的旧主体绑定未决不会被薄适配层偷偷修复。
显式条件/否定/问句沿用旧保守行为；复杂引述标记使内容保守进入未决/未知，不从引用中截取确定事实。

每个片段记录 text、UTF-16 `[start,end)`、kind、reason 和 claimIds；连续片段拼接必须精确恢复整个回答，包括 Emoji、补充平面字符、分隔符和空白。
同一语句中被 Extractor 消费的供应商前缀纳入该片段，关联原始 Claim；分隔符无需虚构 Claim。
Analysis 的 `counts`、`emptyAnswer` 和 `hasUnresolvedContent` 是诊断信息，不是质量评分或完整覆盖通过率。
纯礼貌/纯标点回答可能没有未决内容，但 PARSED_CLAIM 数量为零，不能推出回答完整。状态主张即便已解析，旧逐主张证据判定仍保留 SKIP。

Policy 资源位于 `src/test/resources/procurement/evaluation/procurement-answer-policy-v1.json`：

- schemaVersion=`procurement-answer-policy-v1`，policyVersion=`procurement-answer-requirements-v1`；固定绑定 Benchmark 与 Fixture 原始字节 SHA-256，不自动替换为当前文件哈希。换行或字节内容变化也需显式检查。
- 每个 case 有 elements；每项含 elementId、requirement、sources、rationale、acceptedClaimTypes。加载后显式附带 caseId。
- USER_REQUEST 来源引用该 case 的 userMessage 及原文摘录；BENCHMARK 来源引用该 case 的 JSON pointer 和标量值；SCENARIO_POLICY 是明确约定：`decision-summary-v1` 要求推荐报价/交期摘要，`preference-explanation-v1` 要求双候选解释主要偏好。这两条约定不是由 Agent 回答生成。
- 校验四个 case 恰好覆盖、元素 ID 唯一、必填字段、来源可追溯、已声明 Claim 类型、规则适用性；无合格场景不能要求推荐，单候选不能要求成对比较，非单候选不能要求唯一合格。
- Policy 内容指纹按实际资源字节计算；即使未改版本号，内容修改仍改变指纹。未来比较不能仅检查版本标签。

四个 case 的人工审定要求：

| Case | 必答元素定义 |
| --- | --- |
| delivery_priority_two_eligible | 推荐供应商、其总价、其报价交期、相对另一合格候选交付更快、仍满足预算 |
| price_priority_two_eligible | 推荐供应商、其总价、其报价交期、相对另一合格候选价格更低、仍满足交付期限 |
| single_eligible_after_exclusions | 推荐供应商、其总价、其报价交期、当前约束下仅有一个合格供应商 |
| no_eligible_under_hard_constraints | 明确当前原始硬约束下没有合格供应商 |

没有统一强制业务执行阶段、全部输入字段或全部供应商报价。Policy 中比较、预算/期限、唯一/无合格等 Claim 类型只是声明，7B-2A 不提取或评分这些关系，也不判定必答元素是否缺失。

新版 `ProcurementAnswerEvaluationV2` 关联 case/run、Artifact/回答/Dataset/Fixture SHA-256、Policy 版本和哈希、提取器与覆盖分析器版本、组合评测版本。
`legacyEvaluation` 原样保留旧逐主张事实/支持结果；coverage 的 `claim-N` 与旧 claims 的第 N 项对应，组合时校验两者完全一致。
礼貌片段在旧结果中仍可能是未决，v2 不反写旧结果；后续 B 批次需按覆盖分类区分实质性内容，不能直接把旧所有 SKIP 当成同一种业务阻断。
`requiredAnswerElements` 只是定义，`completenessStatus` 和 `completeAnswerStatus` 强制 SKIP；构造/反序列化均禁止提前设置 PASS。
组合 evaluationStatus 仅保留旧 FAIL/ERROR/SKIP，并补充 Policy/关联异常 ERROR，不产生完整回答结论。覆盖尚未执行时 coverage 为 null，不能当作空的成功覆盖。

`ProcurementAnswerCoverageEvaluator.replay(...)` 只读取 Artifact、Fixture、Policy；输出必须是新文件，已有 v1/v2 报告或输入文件均拒绝覆盖。
未知 Policy 版本、坏哈希、损坏规则等已加载输入问题产生 ERROR sidecar；文件缺失/无法读取则抛出 IOException，不报告通过。JSON 不注入当前时间或输出绝对路径。

```powershell
# 新覆盖/规则测试
mvn -o '-Dtest=ProcurementAnswerCoverageTests,ProcurementAnswerPolicyTests' test

# 用历史 Artifact 显式离线重评；输出必须尚不存在，重复重评请换输出文件名
mvn -o '-Dtest=ProcurementAnswerPolicyTests#explicitHistoricalArtifactReplay' '-Danswer.v2.artifact=target/procurement-evaluation/runtime-artifact.json' '-Danswer.v2.report=target/procurement-evaluation-v2/answer-2a.json' test
```

下一批次可复用 Coverage Analysis、带来源的 Policy.forCase(...).elements()、旧逐主张结果及 v2 身份绑定。本批次没有 Completeness Grader、关系型解析、推荐理由评分、统一报告比较或 LLM Judge。

7B-2A 最终契约复核补充：Coverage 构造及反序列化校验连续区间、原文长度、Claim ID/区间关联、Unicode 边界和统计标志；NON_FACTUAL 仍须满足完整片段白名单。v2 同时核对内嵌旧结果的身份、版本、Claim 列表，以及覆盖原文的回答哈希，拒绝互相矛盾的状态与错误列表。人工场景约定的引用原文必须与指定约定一致。这些校验不增加字段、不改变 schema 或评分口径，也不构成对外部 JSON 的真实性签名：重放时仍需通过原始 Artifact、冻结 Fixture 和 Policy 加载入口验证来源。

### Phase 7B-2B-1：独立关系主张评测

本批次新增 `ProcurementAnswerRelationExtractor`、`ProcurementAnswerRelationGrader` 和端到端测试。
不修改旧 Claim、Grader、Coverage 或 v1/v2 sidecar，不实现必答元素匹配、偏好理由聚合、完整性、完整回答 PASS 或统一 Comparator。
关系结果采用独立的 `procurement-answer-relations-v1` 临时子结果契约，尚非完整 `procurement-answer-v3`。

关系 Claim 保存原文与 UTF-16 区间、left/right、scope、property、operator、BigDecimal difference、unit/currency、modality 和 unresolvedReason。
每个原始非分隔符片段必须整段匹配，否则保留未决。条件、问句和引用范围保守处理；不靠截取正确前缀提升通过结果。
普通标量和礼貌语也可能在关系结果中未决：这是关系专用提取器，不替代旧 Coverage 的分类。

有限语法示例（主体为 `Supplier B` 或明确的单字母 `B`）：

- `D 比 B 快 6 天`、`D 的报价交期比 B 的报价交期更快`；快=交期小于，慢=大于。
- `D 比 B 贵 3 万元`、`D 的总价比 B 的总价高 30000 CNY`；该有限语法中的裸“贵/便宜”明确约定为本次采购总价，不支持单价比较。
- `D 的总价满足本次预算`、`D 的总价在本次预算内`、`D 的总价超出本次预算`。
- `D 的报价交期满足本次交付期限`、`D 的交期超出本次交付期限`。
- `当前原始硬约束下仅 D 合格`（亦支持“当前约束下”）、`当前原始硬约束下无合格供应商`。

差值金额支持元/人民币/CNY、美元/USD、欧元/EUR 及万倍换算；无显式差值币种时绑定 Case 币种，不做汇率换算。
代词、跨句指代、全体最值、单价比较、自由形式理由和混合复杂句保留未决。价格/期限关系不会额外推断该主体就是最终推荐者。

事实侧复用冻结 Benchmark/Fixture 校验与事实索引，增加冻结预算和合格集合；总价由冻结单价乘冻结数量计算，不从工具结果建立真值。
证据侧复用原始工具 JSON 数值与报价来源校验，不经过 SupplierOffer 构造器；从本次最终 Case 获取预算/期限，关系每侧操作数均须有支持并与冻结事实一致。
即使错误工具数值恰好得到相同差值，也不能得到证据支持 PASS。工具 JSON 的重复键、尾随文档、显式错误 caseId 均拒绝。

关系入口限定复用 Phase 7A `procurement-deterministic-v2`，要求九项需求检查各出现一次，以确认此前的 schema、Case/Dataset/Fixture、Run/session、工具请求/结果身份与业务归属校验均已完成。
使用执行证据前，九项检查必须全部 PASS：产品类别/描述非空、quantity、budget、currency、requiredDeliveryDays、hardConstraints、preferences、excludedSuppliers。不以 structuredStatus 或单个检查项存在代替这些门槛。
需求不匹配产生证据 ERROR，但冻结事实仍独立评分；身份校验未完成则整体 ERROR，不继续使用该 Artifact 的业务事实。
旧 Grader 将工具结果解析错误也归入 artifactValidity，因此新入口不将该汇总直接作为所有关系的事实结论；成功越过身份门槛后，工具载荷由关系证据校验独立处理，保留独立事实结果。
Search 必须成功且版本有效；缺少 Search、失败 Search、缺少 eligibleSuppliers 为支持不足；显式空数组才是空集合。
数组类型错误、重复 ID、错误 Case/Run/版本、原始金额自相矛盾、来源过期或多次成功 Search 为 ERROR。
合格集合还需原始约束范围匹配，排除名单按集合比较；缺少 Search 的 offers/evidence 字段不支持合格集合断言。
Search 集合或报价与冻结事实明确冲突为 FAIL；本阶段不重新执行供应商排名或资格算法。

每条结果分别记录 factualDirection、factualDifference、evidenceDirection、evidenceDifference，并附原因和证据路径。
方向和差值绝对值独立判断：`D 比 B 快 5 天` 的方向 PASS、差值 FAIL；没有差值主张时差值为 NOT_APPLICABLE。
PASS 表示该关系检查通过；FAIL 表示可证实的矛盾；SKIP 表示未决或缺少支持；ERROR 表示输入/证据结构或身份异常。
relationStatus 按 ERROR > FAIL > SKIP > PASS 汇总且保留逐项结果，NOT_APPLICABLE 不视作已评分通过；completeAnswerStatus 始终 SKIP。
schema、提取器/评分器版本、Case/Run、Dataset 版本与哈希、Fixture/Artifact/回答哈希随结果保存；无当前时间和输出路径参与指纹。

`replay(case, artifactPath, fixturePath, outputPath)` 只读已有输入，要求输出是新文件；不调用 Agent、模型或业务工具。

```powershell
mvn -o '-Dtest=ProcurementAnswerRelationTests' '-Drelation.artifact=target/procurement-evaluation/runtime-artifact.json' test
```

后续 7B-2B-2 可使用 `extract(answer)` 的原文区间及 `grade(...)` 的独立关系判定关联旧覆盖片段；本批次没有把旧 UNRESOLVED/SKIP 改写为 PASS。

最终复核将独立关系 Grader 提升为 `procurement-relation-grader-v1.1`：修复缺少 eligibleSuppliers 时可能绕过 Case 需求范围检查的问题。提取语法和关系 schema 不变。
关系 Claim 构造/反序列化检查原文长度与区间、语气和差值基本约束；逐项结果禁止未决 Claim 获得 PASS，已解析但无差值的关系强制差值为 NOT_APPLICABLE，方向不能标成 NOT_APPLICABLE。输出仍非真实性签名，应通过原始 Artifact 重评核验绑定，而非信任任意外部修改的 JSON。

### Phase 7B-2B-2：必要元素匹配与有效覆盖

新增测试侧 `ProcurementAnswerCompletenessGrader` 和 `ProcurementAnswerEffectiveCoverage`，复用旧标量、关系、Coverage 和 Policy 入口。
独立输出 schema=`procurement-completeness-coverage-v1`，matcherVersion=`procurement-completeness-v1.1`，覆盖适配器版本=`procurement-effective-coverage-v1`。
内嵌 baseEvaluation（原 v2）和 relationEvaluation（独立关系结果），保留原有 Case/Run、Artifact/回答、Dataset/Fixture、Policy 哈希及所有评分版本与逐主张结论。
这不是完整 v3 聚合：completeAnswerStatus 强制 SKIP，evaluationStatus 仅为 SKIP 或 ERROR，不提供完整回答 PASS。

匹配器仅接受已审定的 Policy v1 原始内容指纹 `2588b6a4c336df497390dadf3fa91e312a2f81e6be3f43c473f9d5c365bfe8c4`。
即使版本号不变，内容或换行改变也不能静默沿用旧匹配语义；未知指纹、版本、数据绑定产生 ERROR，不回退规则。
四个 Case 的原有 15 项定义保持不变。具体匹配是 Java 中有限的逐元素规则，不是 acceptedClaimTypes/关键词自动匹配，也不是通用规则 DSL。

| 元素 | 明确的匹配约束 |
| --- | --- |
| recommendation | 旧标量解析出的确定推荐，所有明确推荐必须指向唯一主体；备选不算推荐 |
| selected_total | 明确推荐主体的 TOTAL_PRICE；其他供应商的报价不能替代 |
| selected_lead_time | 明确推荐主体的 LEAD_TIME；用户期限或成对交期比较不能替代 |
| delivery_advantage / price_advantage | 对应属性的 PAIRWISE 关系，双方是冻结合格集合中的不同候选，其中一方是回答明确推荐者；支持比较双方反向表述 |
| budget_compliance / delivery_compliance | 推荐主体的 CASE_BUDGET / CASE_DELIVERY 关系 |
| unique_eligible | 当前原始约束范围中的 ONLY 关系，唯一主体与明确推荐者一致 |
| no_eligible | 当前原始约束范围中的 EMPTY 关系，不要求推荐或报价 |

主体角色来自明确回答，但规则和适用范围来自固定 Policy/冻结 Case；不会用预期推荐者补全缺失或歧义推荐。
没有明确推荐、却报告了独立报价/交期时，相关 selected 元素标 UNRESOLVED；多个不同推荐也不自动选一个。
同一原子 Claim 不跨元素重复使用；推荐 Claim 只用于确定其他元素的主体角色，不重复计作其他元素的 matchedClaimRefs。
单候选不增加备选要求；无合格 Case 只匹配其已有 no_eligible 元素。

每项输出 elementId、presence、matchedClaimRefs、candidateFragmentRefs 和 reason：

- PRESENT：类型、角色、范围和原文关联明确匹配。错误金额、交期、方向、差值或明确的超预算断言仍可表示内容已表达，事实/关系 FAIL 原样保留。
- MISSING：没有匹配，且不存在剩余实质性未知/未决片段或相关主体歧义。空回答可确定缺少必答内容。
- UNRESOLVED：内容可能表达在未知/未决文本中，或推荐角色无法确定。第一版保守地把所有剩余实质性未知片段关联到每个未匹配元素，不通过关键词猜测其含义。
- NOT_APPLICABLE：契约预留为场景规则明确不适用；当前固定 Policy 已按场景排除不适用元素，因此本版不会生成该状态，也不把规则错误转为不适用。

completenessStatus 只表示信息表达：有 MISSING 为 FAIL；否则有 UNRESOLVED 为 SKIP；全部 PRESENT 为 PASS；匹配未执行为 SKIP。
即使 completenessStatus=PASS，事实仍可能 FAIL、执行证据仍可能 SKIP/ERROR，完整回答仍为 SKIP。各维度不做加权或互相覆盖。

有效覆盖是独立适配结果：保留 original Analysis，每个 Segment 通过 originalFragmentIndex 指向原文 UTF-16 区间，引用 `scalar:claim-N` 或 `relation:N`。
旧 PARSED_CLAIM 保留为 PARSED_SCALAR，NON_FACTUAL 原样保留；只有旧 UNKNOWN_CONTENT/UNRESOLVED_BUSINESS 被确定关系完整消费（仅允许边界空白）时，才标成 PARSED_RELATION。
适配前校验完整回答与旧 Analysis、关系 Claim 列表完全一致；输出反序列化核对身份、原始 Coverage、覆盖解释和元素引用。
关系 FAIL 或证据 SKIP 不阻止“语法已解析”，也不会被改写成业务 PASS。无法解析的相邻保证、未知语句和复杂作用域继续保留。
例如 `D 比 B 快 6 天，但保证绝不延期` 只解析前半片段，后半片段仍未决，hasUnresolvedContent=true。

`grade(case, artifact, fixtureBytes, policyBytes)` 和 `replay(case, artifactPath, fixturePath, policyPath, newOutputPath)` 均离线执行。
replay 要求新输出文件，不覆盖 Artifact、旧 sidecar 或已有报告；同输入同规则输出稳定，无时间戳或输出绝对路径加入评分。

```powershell
mvn -o '-Dtest=ProcurementAnswerCompletenessTests' '-Dcompleteness.artifact=target/procurement-evaluation/runtime-artifact.json' test
```

7B-2B-3 可复用 elements、effectiveCoverage、内嵌旧评分及全部身份/版本绑定。本阶段没有完整回答状态聚合、有效覆盖通过率门槛、统一 Comparator、LLM Judge 或任何生产语义修改。

最终复核：明确推荐存在多个不同主体时，所有依赖推荐角色的未匹配元素保持 UNRESOLVED，并关联推荐片段，不把主体歧义误报为确定遗漏；因此匹配规则提升为 v1.1，schema 不变。
独立 Effective Coverage 构造/反序列化也从原文核对关系引用、完整区间与语气，不只依赖外层结果校验。完整性结果反序列化校验内嵌规则定义与随程序提供的冻结 Policy 一致，并按同一匹配函数核对逐元素结果，拒绝将已有总价/交期引用互换等语义错误。验证只读 classpath 中已绑定哈希的 Benchmark/Policy，不依赖当前工作目录中的 Fixture，也不重新执行 Agent 或业务工具。
保守策略会降低可判定率：即使未知片段实际上无关，未匹配元素仍可能从 MISSING 变为 UNRESOLVED；没有通过语义消歧缩小候选片段。不提供该比例的实测结论，也不会据此将未知内容计成 PRESENT。此契约仍非外部 JSON 真实性签名，Artifact/执行证据真实性须经原始输入重评核对。

### Phase 7B-2B-3 Step 1：v3 契约与可信门禁

新增测试侧 `ProcurementAnswerEvaluationV3`、`ProcurementCompleteAnswerEvaluator` 和包内决策表 `ProcurementCompleteAnswerDecision`。
schema 为 `procurement-answer-v3`，阶段评测器为 `procurement-complete-answer-step1-v1`，待承接结构为 `procurement-assessment-coverage-pending-v1`。
唯一公开评测入口是 `evaluate(case, artifact, fixtureBytes, policyBytes)`；本步没有中间 JSON 组合入口、v3 文件重评入口或报告聚合器。

有效输入返回 `assessmentStage=NOT_IMPLEMENTED`、`assessmentExecutionStatus=NOT_IMPLEMENTED`、显式 `completeAnswerStatus=null`。
该 null 是必需字段的明确未评测值，不是缺失字段的默认值；JSON 缺失此字段将被拒绝。
基础门禁或独立评分异常返回 `assessmentStage=BLOCKED`、执行状态 ERROR 和完整回答 ERROR。
旧事实 FAIL、证据 SKIP、必要元素 MISSING/UNRESOLVED 均保留为 findings；在适用评分尚未实现时不提前聚合为完整 FAIL/NEEDS_REVIEW/PASS。
因此“Step 1 尚未实现完整评测”不被伪装成回答自身需要人工复核。

门禁核验冻结 Dataset 的实际内容及 Case 定义、Fixture 字节哈希、Policy 原始资源字节及固定内容指纹。
复用固定 Phase 7A v2 中全部九项 requirement 检查证明身份验证已完成，并要求需求范围全部 PASS，不使用 structuredStatus 代替。
Phase 7A 把身份和后续 outcome 异常共用 artifactValidity；这里不通过该汇总标签猜测异常阶段。
另校验业务版本、成功 Search/Finalize 原始载荷 Case/version、重复 JSON 字段和尾随内容；Finalize 必须明确提供 Case。
无法可靠分类的基础错误保守为 ERROR，并且不调用后续完整性组合器。
历史 Runtime 会把 null answer 规范化为 ""；本入口据实绑定规范化后的原始 Artifact 回答，不能恢复此前丢失的 null/空字符串区别。

InputBinding 保存 Case/Run/session/业务 Case/version、Dataset 版本及哈希、Fixture/Artifact/回答/Policy 指纹和支持的组件版本组合。
内嵌原 completeness-coverage-v1 结果不改写；顶层与内嵌共同具有的身份字段必须一致，额外 session/业务身份由原始门禁核验。
组件版本明确固定，未知组合拒绝；构造和反序列化检查必需字段、枚举、阶段状态、嵌套身份、完整 findings 和待承接片段。
这些是内部一致性约束，不是真实性签名。读取外部 JSON 不会重新认证其原始业务证据。

待承接片段保留原 Coverage 片段号、文本、UTF-16 区间以及同片段 scalar/relation 候选引用。
所有评分路径均为 PENDING，阻断项为 APPLICABILITY_NOT_IMPLEMENTED，supersededUnresolvedRefs 必须为空；反序列化重新核对候选引用与原文。
本步不会忽略任何旧 SKIP，也不会将候选 Claim 当作已完成评分承接。执行状态、条件、引用及未知业务内容仍保留在旧结果与 findings 中。

包内四态决策表版本 `procurement-complete-answer-decision-v1` 只接收未来可信适用检查，用于隔离规则测试，不在 Step 1 原始入口中宣称适用评分已完成：

| 条件（按顺序） | 完整状态 | 执行异常 |
| --- | --- | --- |
| 基础身份不可信 | ERROR | 是 |
| 身份可信，有适用 FAIL | FAIL | 保留其他检查的 ERROR |
| 无 FAIL，有适用 ERROR | ERROR | 是 |
| 适用检查未完成、有 SKIP，或没有任何实际 PASS 检查 | NEEDS_REVIEW | 否 |
| 所有适用检查 PASS，仅可伴随明确 NOT_APPLICABLE | PASS | 否 |

NOT_APPLICABLE 不算 PASS，空检查集和仅有 NOT_APPLICABLE 的集合不能通过。内部调用方必须明确声明无差值关系检查的位置；未声明的 NOT_APPLICABLE、越界声明或对非 NOT_APPLICABLE 检查的声明均视为决策输入 ERROR。该声明还需由 Step 2 依据真实关系 Claim 生成，不能从任意 SKIP 推导。
已知 FAIL 与独立 ERROR 的决策表测试保留 FAIL 和执行异常；真实入口当前仍保留全部逐项诊断并声明阶段未完成/阻断，不替代 Step 2 的文本端到端适用评分验证。
当前 v3 构造器禁止完整 PASS。未来启用实际承接及最终判定必须显式升级评测器/覆盖版本和合法阶段，不静默改变 Step 1 文件含义。

本步没有修改冻结 Policy、Benchmark、旧 Grader、旧 Coverage、v1/v2 Sidecar 或生产语义；没有实现完整回答 PASS、LLM Judge、统一 Comparator 或真实业务调用。

Step 1 最终复核补强：成功载荷的 caseVersion 必须可无损转换为 long，防止溢出后碰巧等于当前版本；存在的 caseId 必须为字符串。
artifactIdentity 前置检查的 reason 保留原始门禁观察到的 session/业务 Case/version 规范化快照，构造及反序列化与顶层对应字段交叉核对，拒绝只改动顶层额外身份字段。该冗余校验仍不是真实性签名，协同伪造两处必须通过原始 Artifact 重评识别。
即使旧结果因异常缺少 Effective Coverage，新待承接结构也核对原 Coverage 与原文重解析一致、关系 Claim 列表与同一原文提取一致，不能靠异常分支绕过原文约束。

### Phase 7B-2B-3 Step 2：逐片段承接与完整回答聚合

本节描述当前实现；上一节保留 Step 1 历史语义。schema 仍为 `procurement-answer-v3`，评测器升级为 `procurement-complete-answer-step2-v1`，承接规则为 `procurement-assessment-coverage-v1`，决策表为 `procurement-complete-answer-decision-v2`。当前读取器拒绝以 Step 1 版本标识实际聚合结果，不重写任何历史文件。

正式入口仍仅为 `evaluate(case, artifact, fixtureBytes, policyBytes)`。它核验原始输入后重新运行旧 Completeness Evaluation，再由包内 `ProcurementAnswerAssessment` 组合逐项结果；没有外部中间 JSON 组合入口。旧标量、关系、完整性和 Effective Coverage 评分器及版本不变，内嵌结果的完整回答 SKIP 原样保留。

每个 assessment fragment 保存原 Coverage 片段号、原文及 UTF-16 区间、scalar/relation 候选与实际评分引用、必要元素引用、阻断项。评分路径区分标量、关系、非事实、执行状态、条件/引用等限定内容、未知业务和其他未知文本。checks 保存适用评分结果及旧 evidencePaths；承接记录保存被承接引用、支持它的全部 check 引用与确定的承接规则。构造和 JSON 读取重新验证覆盖、引用、findings 和决策一致性；这种校验不是真实性签名，外部 JSON 不能替代原始输入重评。

合法重复 SKIP 承接仅限同一原文片段：

- 标量路径已经完整解析且事实/证据均 PASS，才能承接同片段关系提取器的 `UNKNOWN / UNSUPPORTED_WHOLE_CLAUSE`。
- 完整、明确的有限关系通过全部方向、差值及证据检查，才能承接同区间旧标量解析未决；没有断言差值时，只允许明确的 `NO_DIFFERENCE_ASSERTED` 为 NOT_APPLICABLE，不算一次 PASS。
- 支持检查出现 FAIL、SKIP、ERROR，或组合评分存在异常时，不解除重复未决。仅文本相似、元素 PRESENT 或另一个片段 PASS 均不构成承接依据。
- 旧标量对“原始硬约束”中“约”的保守限定，可由完整且已验证的有限关系消歧；真实条件、问句、引用或跨句限定仍由关系提取器保守保留。相邻业务保证不会随前半句解除。

`supersededUnresolvedRefs` 与 handoffs 一一对应。旧 NON_FACTUAL 完整片段白名单属于适用性分类，使用单独 `nonFactualClaimRefs`，不会伪造事实承接记录；限定语句不适用此豁免。当前 Policy 的 15 项必答元素均依赖事实或关系，没有凭空新增“礼貌表达可满足的必要元素”。

| 条件（优先级从上到下） | completeAnswerStatus | assessmentExecutionStatus |
| --- | --- | --- |
| 基础身份或冻结资源不可信 | ERROR | ERROR |
| 身份可信，存在确定适用 FAIL 或必要元素 MISSING | FAIL | 存在独立异常时 ERROR，否则 COMPLETE |
| 无 FAIL，存在必要评分异常 | NEEDS_REVIEW | ERROR |
| 无 FAIL/ERROR，存在证据不足、未知/限定内容或必要元素 UNRESOLVED | NEEDS_REVIEW | COMPLETE |
| 全部适用必要元素 PRESENT、全部实质主张已可信评分、事实与证据通过、无剩余阻断 | PASS | COMPLETE |

上述独立评分异常规则采用 Step 2 审定表，与 Step 1 决策 v1 区别由版本显式表达。基础异常为 BLOCKED，身份可信后的实际评测为 ASSESSED。空检查集合、纯 NOT_APPLICABLE、纯礼貌或空回答不能 PASS。执行状态主张仍受旧证据能力限制，不因其他事实正确而豁免。旧评分异常可能留下 coverage 缺失及 policyVersion=UNKNOWN；仅允许其原有错误分支原样内嵌，顶层 Policy 仍经原始字节核验，评分异常阻止 PASS。

测试从原始回答和 Artifact 到最终 v3 结论：四个 Case 的人工正确回答各自有撤销 PASS 的变形测试，覆盖全部 15 个必要元素删除、事实/方向/差值/主体错误、证据删除、额外未知保证、执行状态、条件和引用。另验证独立 FAIL+ERROR、重复解析隔离、同输入输出字节稳定、JSON 字段重排及工具顺序变化后的引用与证据重绑定。

人工答案与真实输出分别报告：四个人工构造回答及合成执行证据的正例可以 PASS；已保存 scripted Runtime 的 delivery_priority_two_eligible 原始回答为 FAIL，缺少 delivery_advantage 和 budget_compliance，“仅完成只读推荐”仍有执行状态证据未决。这不是四个 Runtime/真实模型通过的结论，没有重新运行 Agent 或真实模型。

证据来源补充：上述四个正例是 `executionMode=HANDCRAFTED` 的合成 Artifact，不是只替换历史 Artifact 的回答。测试从冻结 Fixture 生成报价，并自行构造 Search、Finalize、OFFER 证据、时间及来源字段；其 runId=run、sessionId=conversation、businessCaseId=business-case。它们仅验证确定性评分与撤销条件，不能作为真实执行证据验收或真实 Runtime PASS 结论。正式调用方必须提供真实捕获的 Artifact；当前门禁验证内容一致性和身份绑定，不证明 Artifact 外部来源真实性，也不会自动区分精心构造的合成输入和真实捕获输入。

最终定向审计补充反例：相同原文区间但篡改关系差值并复制 PASS 评分，反序列化必须拒绝；不分隔的关系后缀业务保证必须保留阻断；Artifact metadata 注入外部 v1/v2/关系 PASS 标签不影响原文重新评分。15 项删除变形同时检查必要元素诊断和旧成功匹配引用撤销；同一事实 FAIL+评分 ERROR 样例再破坏基础身份时，只输出基础 ERROR，不沿用不可信局部 FAIL。

PASS 只表示当前版本规则、有限确定性表达和有效捕获证据范围内通过，不代表任务完成、模型实际看到了全部证据或外部副作用证明。未知实质片段仍会使完整性匹配保守 UNRESOLVED。Step 2 不提供 v3 磁盘重评入口、文件不覆盖验收、统一 Comparator 或历史 v3 迁移；这些不由内存聚合替代。

### Phase 7B-2B-3 Step 3：独立 v3 磁盘重评与兼容验收

新增 `ProcurementAnswerV3Reports`，不修改 Step 2 的评分器、版本或状态规则。
`replay(artifactPath, fixturePath, policyPath, outputDirectory)` 从原始 Artifact 选择冻结 Dataset Case，读取 Fixture/Policy 实际字节，调用原有 `ProcurementCompleteAnswerEvaluator.evaluate(...)`，返回新 Sidecar 的 Path。
不接受历史评分 JSON 作为输入，不调用 Agent、模型或采购工具。`read(sidecarPath)` 独立读取并验证当前 v3 契约。

文件名为 `procurement-answer-v3-<SHA-256 of canonical result bytes>.json`。指纹覆盖完整结果（含输入身份、评分版本、诊断、原文引用），不只是 Case 名或回答文本。
相同输入/版本在不同目录输出相同文件名和完全相同字节；不加入评测时间、随机数或绝对环境路径。相同目录重复输出抛出 FileAlreadyExistsException，不静默覆盖。
读取时同时验证文件名指纹、schema、record 契约与规范化字节，拒绝改名、篡改、重复 JSON 字段、尾随内容和伪造 PASS 的 v1/v2 文件；即使将旧文件重命名为正确内容指纹也不能升级为 v3。
JSON 数字直接从原始字节绑定到 record，避免通用树转换改变 BigDecimal scale 而破坏旧 Coverage 的精确一致性。

先在输出目录创建 `.answer-v3-*.pending` 文件，写完并核对完整字节后，通过同目录硬链接发布正式文件。创建链接不替换已有文件，能拒绝并发占用、输入或历史文件的别名；无覆盖式 move 回退。
写入异常或短写清理 pending，正式名称不会暴露半成品。进程被强制终止可能遗留 pending，但其名称不能由 v3 读取器接受。此方案要求文件系统支持硬链接；不支持时明确失败，不保证断电持久性，也不提供来源真实性签名。
输出目录参数指向已有文件时直接失败。Artifact JSON 损坏、重复字段或未知 Case 在输出前拒绝；可解析但未通过原有可信门禁的输入保留 BLOCKED/ERROR 结果，不补默认事实。

定向验收入口（测试使用独立临时输出目录）：

```powershell
mvn -o '-Dtest=ProcurementAnswerV3ReplayTests' '-Danswer.v3.artifact=target/procurement-evaluation/runtime-artifact.json' test
```

四个 HANDCRAFTED 合成 Artifact 的 PASS、未知保证撤销 PASS、重复字节、UTF-16 原文与阻断引用均经磁盘往返验证；合成证据不代表真实执行验收。
保存的 scripted Runtime 回答重评仍为 FAIL，并与 Step 2 内存结果逐字段相等，原始文件不变。
兼容验收继续显式运行 Phase 7A、标量 v1、Coverage v2、关系及完整性五个旧重评入口；旧 Sidecar 的完整回答 SKIP 不改写，历史文件不迁移。本步不实现 Comparator。

### Phase 7B-2C Step 1：比较身份与可信输入

新增测试侧 `ProcurementExperimentManifest`（`procurement-experiment-manifest-v1`）与 `ProcurementComparisonInputs`（`procurement-comparison-inputs-v1`）。入口为 `validate(manifestPath)`，只读输入，返回比较资格及诊断；不写比较报告、不排名、不计算胜率、综合分数或统计显著性。

Manifest 必需字段：schemaVersion、experimentId、datasetVersion、datasetSha256、fixturePath、policyPath、requiredRecordsPerCase、groups、records。路径按 Manifest 所在目录解析，也允许绝对路径。
每个 group 声明 groupId、model、promptVersion、agentVersion 和 configuration；这些字段明确属于实验声明，不代表模型/Prompt/Agent 已被真实调用证明。
每条 record 声明 recordId、groupId、caseId、runId、sessionId、artifactSha256、artifactPath、v3Path。比较结果复用 v3 的完整 InputBinding，不另造评测身份；业务 Case/version、回答、Fixture、Policy、组件版本来自正式 v3，而非 Manifest 补值。

验证先读取冻结 Dataset 与 Fixture/Policy 实际字节，再通过 Step 3 的 `ProcurementAnswerV3Reports.read` 读取每个 v3。有效绑定必须与 Manifest 的 Case/Run/session/Artifact 声明一致；原始 Artifact 经旧读取器及重复 JSON 字段校验后，调用原有完整回答入口重评，要求整个结果相等。不会只读取 completeAnswerStatus，也不会把历史评分结果当作 Ground Truth。
本版只接受当前已支持的精确规则组合：历史/未知 schema、Policy 或评分器版本由正式读取器拒绝，资源不一致或原始重评不一致均 NOT_COMPARABLE。没有跨版本兼容映射，不静默合并规则口径。

比较资格独立于回答状态：可信的 PASS、FAIL、NEEDS_REVIEW 可以同层比较，并保留执行异常状态；基础门禁 BLOCKED/ERROR 缺少可信身份，标记 FOUNDATION_ERROR_NOT_AN_ANSWER_FAILURE，不能当作普通回答失败纳入。
requiredRecordsPerCase 预先规定所有实验组共同的 Case 范围及每 Case 记录数。scope 只描述声明范围：声明包含全部冻结 Case 时为 FULL_FROZEN_BENCHMARK，显式子集为 DECLARED_SUBSET；它不是实际完成标志，必须同时检查 eligibility。按 Case 返回各组的记录引用及资格；任何缺失、额外记录或无效输入都保留诊断并阻止整体 COMPARABLE，缺失项不生成假 FAIL，也不删除 Case。部分 Case 合格不代表整个实验完整。

重复判定使用正式绑定中的 Artifact 哈希和 Run ID，而非回答或评分内容相似性。相同 Artifact、重复 recordId、复用 Run ID 的所有相关引用均阻断，跨组改标签不产生新样本；同一 groupId 的重复/冲突定义也拒绝。
不同 Run ID/Artifact 即使答案完全一致仍保留，但只称“不同捕获运行身份”。结果固定声明 INDEPENDENCE_NOT_ATTESTED，不输出独立样本量，不证明改写 ID 后的输入是真实独立执行；Manifest 标签、内部一致性和原始重评都不是来源真实性签名。

组、记录、Case 和诊断按稳定键排序；输入列表重排不改变资格和诊断。Manifest 缺字段、未知版本/字段、重复 JSON 字段和尾随 JSON 直接拒绝；文件缺失、绑定冲突、资源错误则由记录/范围诊断定位。输入 v3 的状态可保留用于诊断，只有资格合格的记录进入 Case 比较集合。
测试数据均标 SYNTHETIC_TEST_ONLY，复用已有 HANDCRAFTED Artifact 工厂；不作为真实模型能力或运行独立性的证据。历史 Artifact/v3 文件只读，旧五个磁盘入口继续独立兼容验收。

最终定向审计：完整 record 相等检查覆盖 Claim、证据诊断、承接与阻断项，不只比较最终状态。反例包括同为 FAIL 但原始证据变化、两组总数相等但 Case 分布不同、跨路径复制同一 Artifact/v3，以及合法记录已满额但另有坏记录；均不能获得整体比较资格。身份可信且重新评分一致的独立评分 ERROR 保留在 assessmentExecutionStatus 中，可与 FAIL/NEEDS_REVIEW 同时存在，不被当作基础身份失败。

### Phase 7B-2C Step 2：分层指标与描述性差异

`ProcurementComparisonAnalyzer.analyze(manifestPath)` 返回内存契约 `ProcurementComparisonMetrics.Result`，版本为 `procurement-comparison-metrics-v1`。不接受外部拼装的评分对象；先调用 Step 1 `validate`，整体 NOT_COMPARABLE 时返回 BLOCKED、全部输入诊断和空指标集合，不能挑选剩余合法记录继续比较。Malformed Manifest 沿用正式入口的读取异常。COMPARABLE 后通过正式 v3 读取器取得完整记录，并核对读取前后的输入快照及绑定；变化时阻断。该快照检查不是任意并发攻击或来源真实性证明。

结果保留规范化 Manifest 指纹（组和记录列表排序后计算）、Step 1 声明身份/范围/资格、每组每 Case 的预期及有效记录数、全量 v3 `recordEvidence`、指标和差异。Manifest 指纹标识规范化声明，不是原文件字节哈希或真实性签名。每个桶带具体 recordId、Case、Claim/检查/元素/片段引用、原因及证据路径或相关引用；用 recordEvidence 可继续定位 Run、原文 UTF-16 区间、证据诊断、合法承接和阻断项，不复制事实判定逻辑。

| 指标 | 分母与状态 |
| --- | --- |
| COMPLETE_ANSWER | 当前组/Case 全部可信记录；PASS、FAIL、NEEDS_REVIEW、ERROR 分别计数，PASS 比例只以 PASS 为分子 |
| SCORING_EXECUTION | 同一批可信记录；COMPLETE/ERROR，与回答状态分开 |
| SCALAR_FACT / RELATION_DIRECTION / RELATION_DIFFERENCE | 对应实际评分检查，PASS/FAIL/SKIP/ERROR 全部进入分母；N/A 和 v3 证明已承接的重复未决显式列入 exclusions |
| SCALAR_EVIDENCE / RELATION_DIRECTION_EVIDENCE / RELATION_DIFFERENCE_EVIDENCE | 对应证据检查，PASS=支持、FAIL=检查不支持、SKIP=不足或未决、ERROR=执行异常；详细原因及路径原样保留 |
| SCALAR_VERIFIED / RELATION_VERIFIED | 实际 Claim 的事实与证据联合满足情况，FAIL > ERROR > SKIP > PASS；这是联合诊断，不是新增事实判定，证据 FAIL 不代表事实 FAIL；组件仍独立展示 |
| REQUIRED_ELEMENTS | 当前 Case 冻结 Policy 的全部适用元素；PRESENT/MISSING/UNRESOLVED，评分结果缺失则 UNAVAILABLE，不虚构 MISSING；四 Case 共 15 项 |
| EFFECTIVE_COVERAGE | 原始 Coverage 对应的实质片段；PARSED_SCALAR/PARSED_RELATION/UNKNOWN_CONTENT/UNRESOLVED_BUSINESS；解析比例不是事实或证据通过率 |
| ASSESSMENT_COVERAGE | 实质片段是否仍有阻断；RESOLVED/BLOCKED，保留原始阻断引用 |

NON_FACTUAL 片段和 v3 明确标记的非事实 Claim 按已有适用规则排除，记录原因；不会依据 SKIP 原因文本自行豁免。无检查时保留 NO_CHECKS_AVAILABLE；不推断未提取 Claim 的数量或把它视为正确。旧评分器为空回答输出的未决诊断仍按原状态统计。当前冻结 Policy 无 N/A 元素，本版没有跨 Policy 兼容映射。零分母的比例为 NOT_COMPUTABLE/value=null，不能用 0 或 1 替代。

每个指标分别输出 micro 和 macro：micro 合并同单位的底层贡献；macro 先算各 Case 成功比例，再等权平均，任何声明 Case 不可计算时整个 macro 为 NOT_COMPUTABLE，并列出该 Case，不删除它后继续平均。比例保留 12 位小数，HALF_EVEN；macro 使用上述 Case 比例。两者均无跨维度综合分数。例：第一 Case 缺少 1/5 元素，其余 Case 完整，元素 micro=14/15，macro=(4/5+1+1+1)/4=0.95。

差异按相同 Case、相同指标、相同状态分别给出右组减左组的数量及比例变化，并附两侧贡献来源。数量差为零仍保留元素/Claim 来源，可能存在不同元素缺失；这不是逐运行配对或因果归因，不按数组位置或 Run 排序配对。跨 Case 仅有明确标注的 micro/macro，不强行输出一个能力分数。保留 INDEPENDENCE_NOT_ATTESTED，不输出胜率、显著性、置信区间或独立样本量。

当前 v3 的完整回答 ERROR 只由基础门禁失败产生，Step 1 会阻断该 Manifest，因此正式分布保留 ERROR 桶但当前有效集合中为零。可信的独立评分异常以 assessmentExecutionStatus=ERROR 参与描述统计，回答仍可能是 FAIL 或 NEEDS_REVIEW；不能为了构造四态混合测试而放宽资格或篡改 Sidecar。

端到端测试从 HANDCRAFTED 合成 Artifact 生成正式 v3，再经 Manifest/Step 1 生成指标；金额、方向、差值、额外错误、证据缺失、未知保证和必答元素删除均改变相应维度。合成工具证据只用于验证统计实现，不证明真实模型能力。可选 `answer.v3.artifact` 测试只读保存的 scripted Runtime Artifact，预期仍 FAIL，具体缺失 delivery_advantage/budget_compliance；对照组合成 PASS 明确标记 SYNTHETIC，不能据此作模型排名。没有新增 Runtime 或 Live Eval 执行。

本步不提供比较报告磁盘写入、最终报告读写契约、防覆盖或可视化；这些属于尚未实施的 Step 3。历史评分器、版本和文件契约不变。
