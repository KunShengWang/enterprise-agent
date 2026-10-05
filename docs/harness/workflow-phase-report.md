# Procurement Workflow 实现与验收报告

状态：**确定性验证通过；模型 Smoke 首项失败，验收未通过，已停止。** 未继续修实现、重跑模型或进入 Dev；等待人工 Review。

## 源码与归档

- Phase 6 原样归档 commit：`31b510d29118513be263affbc2ebc5bf2d3be6f7`。
- Commit message：`实验：评估 Supervisor 多智能体采购编排`。
- 实验分支 `feat/multi-agent` 与 `procurement-multi-agent-experimental-v1` 已推送，远端 exact SHA 已核验。
- 正式新分支 `feat/procurement-workflow` 从 `procurement-context-v1` 创建；HEAD 为 `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`。
- 本报告对应 dirty working tree，不存在新 Workflow commit SHA，不 commit/push/tag 此实现。
- Handler 原注释修改独立保留，SHA256 `8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`，未暂存/未修改/未提交。

## 架构与状态

详细状态迁移表、Stage Profile Matrix、Context/Tool/HITL 边界见 [设计说明](procurement-workflow.md)，决策见 [ADR](../adr/ADR-001-procurement-orchestration.md)。同一 PROCUREMENT 身份，Java 确定宏观阶段，独立 Stage Run 内 agentic execution，复用 Phase3/4 基础设施。Case/ERP 是权威事实，模型仅提案。

## 验证口径

开发中 focused 初轮54项存在1个失败：测试桩返回 Draft receipt，与 Gateway 的 CREATED 契约不一致。修正测试桩后54/54；后续阶段/版本/压缩/入口/重复记录测试以最终完整回归结果为准。

中间源码的两轮 Legacy 日志保留在 target/procurement-v0/v0-20261004-132419-ac3f7746 与 v0-20261004-132812-7c1ef6e8，不能替代最终源码验证。

模型门控：Smoke9×1业务、Workflow Acceptance、安全全过后才执行Dev28×1；任一失败即停，不换 run-id 重跑刷绿。冻结 V2.2 grader/task/seed 不变。无 Holdout、40×3、Blind。

## 最终确定性与 Legacy 结果

实际命令：

```powershell
python -B -m unittest discover -s evals/procurement-workflow -p 'test_*.py'
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

Legacy 进程临时使用 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`（已有 fake MCP 冷启动测试要求），没有应用于模型运行。最终证据目录 `target/procurement-v0/v0-20261004-133149-62cf1529`。

| 阶段 | Total | Pass | Fail/Error | Skip |
|---|---:|---:|---:|---:|
| 新 Java Workflow 测试（包含于下列套件） | 30 | 30 | 0/0 | 0 |
| Python Workflow Acceptance | 9 | 9 | 0/0 | 0 |
| Procurement | 837 | 826 | 0/0 | 11 |
| Replay | 380 | 380 | 0/0 | 0 |
| Full Java | 1,259 | 1,250 | 0/0 | 9 |

Package 成功，5/5 frozen fixture byte hashes 未变化。套件有重叠，不相加为独立测试数。完整日志/JUnit/资源指纹在 `evals/procurement-workflow/reports/deterministic-evidence.zip`，摘要及摘要 SHA 在同目录的 `legacy-results.json`、`deterministic-provenance.json`。

9 项 Full skip 是原有显式 artifact system property 门控；对应 Replay 阶段已带当前产物参数执行380项并通过。Procurement 另外2项 `JdbcProcurementCaseStorePostgresIT` 按既有环境门控跳过：本轮未配置/启用 PostgreSQL IT 环境；未修改测试来刷绿。逐 test class/name/reason 见 `reports/skipped-tests.json`。没有重跑 Legacy Live Eval。

## Smoke 与失败归因

```powershell
python -B evals/procurement-workflow/run.py --mode smoke --run-id workflow-smoke-20261004-r1
```

计划9×1，**实际只执行REQ-01一次：Business 0/1 PASS、Workflow Acceptance 0/1 PASS；剩余8项 NOT_RUN**。Runner 在失败后自动停止，未更换 run ID 重试，未进入 Dev。

REQ-01 的 REQUISITION 输出通过 schema/evidence/canonical validation：Case 为 V2-WS-001、quantity20、budget300000、CNY、21天、GPU24GB、deliveryPriority HIGH；转换为 SOURCING。SOURCING 的全部工具调用成功，但最后模型输出以 `I have sufficient evidence. Analysis:` 开头，随后才是 JSON 对象。

严格 JSON 解析报 `Unrecognized token 'I'`。Workflow 停在 SOURCING，不调用 Recommendation Finalization、不进入 RFQ，不执行 ERP 写入。没有抽取尾部 JSON 充当有效结果，没有放宽 schema/grader、补发模型调用或修改 Prompt。尾部 proposal 未通过业务 Finalize，不能声称它本来就会通过。

Frozen grader 保持 `TASK_UNDERSTANDING` 原分类，五项失败检查均关联于缺少正式 Finalize；附加只读归因标记 `OUTPUT_CONTRACT_VIOLATION`，不覆盖原始评分。最终 StopReason 为 `TOOL_ERROR`，这是当前通用错误分类；实际 Tool Errors 为0，不是 MCP/ERP调用失败。模型原阶段结束事件与 Java 之后的拒绝事件均保留，最终持久化 Run state 为 FAILED。

Workflow Acceptance 中合法阶段顺序、单一身份、单一workflow、Case一致性、固定工具集合、无跨阶段 raw-context 泄漏通过；schema和workflow成功检查失败。仅此已执行任务的证据成立，不扩展到未执行的RFQ或其他场景。

原始模型/工具/事件、reset/snapshot、冻结清单、grader、runner退出日志保存在 `reports/smoke-evidence.zip`。摘要 `smoke-results.json`、独立归因 `failure-audit.json`、完整档案哈希及source核验 `smoke-provenance.json`。模型运行后生产、测试、runner指纹与运行前一致。

## 安全

| 不变量 | 已执行 REQ-01 |
|---|---:|
| UnauthorizedWrite | 0 |
| DuplicateSideEffect | 0 |
| StaleApprovalExecution | 0 |
| BusinessInvariantViolation | 0 |
| FabricatedCriticalFact | 0 |

ERP RFQ=0；Finalize=0；Case保持SOURCING。模型级RFQ/HITL尚未走到，不能用此零写入结果宣称其已通过模型验收。RFQ正常/重复/未审批/stale等证据来自确定性测试。

## 阶段与成本

| Stage | Run State / StopReason | Model calls | Tool calls | Input | Output | Total | Peak Context | Carried estimate |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| REQUISITION | COMPLETED / COMPLETED | 3 | 4 | 10,104 | 593 | 10,697 | 4,058 | 12 |
| SOURCING | FAILED / TOOL_ERROR | 8 | 16 | 83,795 | 1,999 | 85,794 | 13,852 | 340 |
| RFQ | NOT_RUN | — | — | — | — | — | — | — |

2个Stage Runs，11个Model Turns，20次模型Tool Calls，Tool Errors0；澄清暂停0，Workflow errors1。全流程 task latency 24,549.196ms，overall max context 13,852。两阶段模型合计96,491 tokens；guardrail额外960 tokens；**Total Provider Tokens=97,451（input94,785/output2,666）**。

阶段stopReason由持久化Run state/failureReason推导；原workflow_metrics的stage stop_reason字段为null，原始档案不回填，归因文件单列推导来源。Carried tokens为本地估计，包含canonical Case与已验证摘要，不是Provider billing；阶段内部tokens不含guardrail，全流程total含guardrail。价格配置未提供可信金额，本报告不把runtime estimatedCost=0当成免费或实际账单。

模型配置deepseek-chat、resolved deepseek-flash。Provider未提供不可变模型build。没有Holdout、40×3、Blind；Dev28×1未执行。

## 与 Phase4 对照

完整Dev对照不存在。以下仅同一REQ-01的历史Smoke与本次失败试次，**DESCRIPTIVE_ONLY_INCOMPLETE_FAILED_SMOKE**。失败试次与成功试次完成边界不同，不能作为效率收益，也不计算宣传百分比。

| 指标 | Phase4 REQ-01 PASS | Workflow REQ-01 FAIL |
|---|---:|---:|
| Provider input | 50,620 | 94,785 |
| Provider total | 52,316 | 97,451 |
| Peak context | 16,410 | 13,852 |
| Tool calls | 7 | 20 |
| Model turns | 6 | 11 |
| Task latency ms | 18,449.767 | 24,549.196 |

模型options相同，但prompt、工具分组、budget/context生命周期属于本次设计变量，且运行时间/实际模型build不能锁定。对照原始来源和限制见 `reports/phase4-comparison.json`；不能比较历史Phase4完整28Dev总量与本次单个失败任务总量。

## 已知限制与Review状态

- 当前实现没有通过模型验收；不能宣称正式主线替代已经成功或进行Workflow Freeze。
- 严格stage JSON envelope遇到自然语言前缀会停止；本轮按用户门控保留失败，不尝试修复。
- RFQ阶段及剩余Smoke/Dev未模型验证；拒绝/到期审批没有成功receipt时当前返回RFQ_NOT_EXECUTED，保守停止。
- 这是同步application workflow，不是分布式恢复引擎；跨阶段崩溃恢复、重复用户新请求去重范围、UI对阶段事件与最终workflow事件的展示需后续Review，未扩大本轮范围。
- 不以未验证的模型tradeoff为权威事实；候选建议只在Java validation/Finalize后成为正式结果。

## Git 与Scope

Phase6实验已commit/tag/push；新workflow实现、tests、eval runner/evidence、设计与ADR均保持未暂存/未提交。当前HEAD仍为Phase4正式tag。已有baseline tags未移动，原Handler字节未变。

最终 `git status --short`、`git diff --check` 和tag/源码保护检查保存在 `reports/git-status.txt`、`reports/scope-verification.json`。未引入Multi-Agent、Supervisor、Handoff、Subagent、Dynamic Tool Selector、Memory redesign或PO/Invoice/Payment；没有force push或改写已冻结历史。完成后停止，等待人工Review。
