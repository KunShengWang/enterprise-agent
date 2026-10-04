# Phase 6：Multi-Agent 实现与未通过的模型验收

> 最新 ANOM-01 恢复后单点验收：Runtime 正确拒绝两个 unsupported candidates，冻结 Business Grader PASS；Supervisor 最终文本又声明未经验证的预算硬阻断，Final-State Consistency / Acceptance v1.1 FAIL。唯一有效 trial 已保存，未修复或重跑。见 [phase6-anom01-infra-recovery-acceptance.md](phase6-anom01-infra-recovery-acceptance.md)。以下为历史记录。

> 最新 Blocker Candidate 修复：focused Java 88/88、Python 29/29、Legacy 全量 1273 PASS / 9 SKIP；唯一 ANOM-01 attempt 因 Docker Linux Engine 不可用在 reset 阶段失败，未调用模型，模型验收未完成。已停止且未重试。见 [phase6-blocker-candidate-acceptance.md](phase6-blocker-candidate-acceptance.md)。以下为历史记录。

> 最新 Acceptance v1.1：纠正 optional-path coverage 过度约束，旧 REQ-03 artifact 离线重评 PASS、旧 FAIL 保留；新增 ANOM-01 出现真实 CHILD_OUTPUT_INVALID / business FAIL 后停止。剩余 6 Smoke、Dev 未运行。见 [phase6-acceptance-v1.1.md](phase6-acceptance-v1.1.md)。以下为历史记录。

> 最新 Gate D1：新 REQ-01 四层 PASS；新 REQ-03 business/consistency PASS，但未调用 Requisition、无成功预算 receipt，按本次专项证据要求记录 acceptance coverage FAIL。已停止，其余 7 Smoke 与 Dev 未运行。见 [phase6-final-d-smoke.md](phase6-final-d-smoke.md)。以下状态保留历史语境。

> 当前最新结果：Root / Child Execution Scope 修复后唯一一次 REQ-01 business grader、Phase-6 acceptance 和 SupervisorFinalStateConsistency 均 PASS；已停止等待人工 Review，完整 Phase 6 Smoke / Dev 尚未通过验收。见 [phase6-execution-scope-acceptance.md](phase6-execution-scope-acceptance.md)。下列早期状态按历史时点保留。

> 最新最终源码验收：C1 在 REQ-01 发现 Supervisor 误将 Child 局部 executionFacts 解释为完整请求状态，离线验收 FAIL 后停止；冻结 grader PASS 保留，其他 Smoke / Dev 未运行。见 [phase6-final-acceptance.md](phase6-final-acceptance.md)。以下为历史记录。

> 最新第二次 Contract 修复：REQ-03 单次 frozen grader PASS，仍待人工 Review，未运行其余 Smoke/Dev。见 [phase6-contract-fix-acceptance.md](phase6-contract-fix-acceptance.md)。以下旧验收结果保留历史语境。

**Disposition：未通过 Phase 6 Quality Gate，待人工 Review。** 最新分 Gate 验收见 [phase6-postfix-acceptance.md](phase6-postfix-acceptance.md)：post-fix REQ-01 PASS，Gate B 的 REQ-03 FAIL 后停止，未运行 Dev。下文保留首轮实现、pre-fix 失败及其离线修复时点的历史记录；其中“尚未模型验证”及原 37 trial 预算描述以该时点为准，不代表最新进度。pre-fix 原始 artifact 未修改。

## 1. Pre-change Responsibility Audit

Phase 4 Single Agent 同时承担意图、Case 补全、Requisition/Item/Budget、Supplier/History/Performance、Recommendation、RFQ 与用户解释。Java 始终负责 Eligibility、Evidence、Finalization、HITL、CAS 和幂等。编码前审计与原职责图见 [multi-agent.md](multi-agent.md)。

## 2. Final Architecture

从 `procurement-context-v1` / `2f649d6cbc56cea1803ffa6e5a3561d48a53d776` 创建 `feat/multi-agent`。不继承 Phase 5。只有 Supervisor + Requisition / Sourcing / RFQ 三个 Domain Agent；用户会话控制权始终在 Root。

```mermaid
flowchart TD
  U[User] --> S[Procurement Supervisor / Root Run]
  S --> T[AgentToolAdapter]
  T --> E[SubAgentExecutor / existing Runtime]
  E --> R[Requisition Child]
  E --> C[Sourcing Child]
  E --> F[RFQ Child]
  R --> D[Responsibility-scoped Domain Tools]
  C --> D
  F --> D
  D --> RT[ToolRuntime / Policy / Java Finalize]
  RT --> MCP[Procurement MCP / Canonical Adapter / ERPNext]
  RT --> A[RFQ Approval interruption]
  A --> S
  S -->|resume same child and exact request| F
```

## 3. Agent Tool Matrix

| Agent | 初始可见工具 | 禁止能力 |
|---|---|---|
| Supervisor | 4：inspect + 3 Agent Tools | 细粒度 ERP reads、直接 RFQ |
| Requisition | 7：Patch + Requisition/Item/Budget/History reads | Supplier Performance、RFQ、子委派、Memory write |
| Sourcing | 10：Search/Finalize + Supplier/Item/History/Performance 等 reads | Case Patch、RFQ、子委派 |
| RFQ | 4：原 RFQ intent + Requisition/Supplier/RFQ reads | 改 Case、重新寻源、自批准、子委派 |

具体完整名称见设计文档。单次工具使用后仍遵守原 single-use 语义；不存在 Phase 5 Stage ToolSelector。工具不可见不能替代授权，ToolRuntime 另有 profile 权限检查，外部写仍走原治理链。

## 4. Structured Outputs

公共 contract：`status / findings[] / warnings[] / evidenceRefs[]`；服务器附加 agentId、childRunId、Case version、objective 和 budget。

- Requisition：额外的阻断枚举、待调查枚举、原申购 ID 和 departmentId；Java 检查 canonical reads 并给出 assessment。READY 必须有完整 Case；Sourcing 调查项不得构成 Requisition 阻断。
- Sourcing：COMPLETE 必须有成功 Java Finalize；返回精简 recommendation 和 receipt ref。NO_ELIGIBLE_SUPPLIER 必须有 Java Search 的空 eligible list。
- RFQ：COMPLETE 与实际成功 execution receipt 一致；返回 CREATED / NOT_CREATED。

非法结构/未知 Evidence 按 CHILD_OUTPUT_INVALID 终止，不把 prose 变成权威事实。

## 5. Context Isolation

Root 保留用户会话与高层摘要；Child 只接收服务端 Task Packet、fresh canonical Case、自己的 Timeline 和工具结果。完整 Child Trace 不返给 Root。Phase 4 ContextAssembler / Projection / Compaction 算法未改。每个 Agent 复用 ContextPolicy；Domain 不写长期 Memory。

## 6. Nested HITL

确定性测试验证：Root WAITING_APPROVAL、Child 持久化、批准前 0 次模拟外部写；批准后恢复同一个 Child、exact request，写入 1 次；重复恢复不新增，拒绝/篡改/stale Case 不写入。原 RFQ 安全测试继续通过。

Run/Tool records 序列化、重建 Runtime 后的审批恢复通过。真实 PostgreSQL 重启恢复未验证；此次真实 Smoke 停在 Requisition，未覆盖真实 ERP 的嵌套审批闭环。

## 7. Parent/Child Runtime

复用 RunState / RunBudget / Timeline / Tool Store，增加可信 lineage 和 WAITING_APPROVAL AgentTool checkpoint。Child ID 绑定 Root + Tool call ID，callDepth ≤ 1。

Root 预算：80 turns / 80 model calls / 140 tools / 1.5m input / 50k output / 480s。Child：24 / 24 / 50 / 384k / 16k / 240s。Root 最多 12 次 AgentTool 记录。Child 消费聚合至 Root，预算、timeout、fatal、取消均有停止路径。Root lease 覆盖同步 Child 的等待期。Child 取消当前以 CHILD_RUN_FAILED 向 Root 传播并安全停止。

当前 Run 内相同领域/目标/Case version 可复用结果。没有通用 Cache、额外业务主数据 Store 或第二套 Runtime。多进程执行中途崩溃恢复并未做真实环境验收，不把审批 checkpoint 的序列化测试扩大为完整故障转移保证。

## 8. Deterministic Tests

Smoke 前 focused 88/88 PASS。失败后的最终 focused 命令：

```powershell
mvn -B '-Dtest=ProcurementMultiAgentTests,RequisitionAssessmentTests,ProcurementRfqHitlTests,ErpNextRfqGatewayTests,DefaultAgentRuntimeStateTests,HarnessCoreTests,ContextEngineeringTests,EnterpriseAgentApplicationTests' '-Dsurefire.reportNameSuffix=phase6-l0-repair-final' test
python -B -m unittest discover -s evals/multi-agent -p 'test_*.py' -v
```

最终 Java focused **94/94 PASS，0 failure / 0 error / 0 skip**。Python **2/2 PASS**，检查 Required/Forbidden rubric 与分角色 token 不重复计数。

新测试覆盖三个 Registry、四 Root tools、无递归、Context isolation、Evidence/schema、直接权限绕过、child fatal/预算耗尽、aggregate budget/cancellation、lease、Nested HITL approve/reject/stale/tamper/duplicate/序列化恢复，以及 Smoke 暴露的不同数量误判重复、跨领域待办误阻断。

## 9. Legacy Regression

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

Smoke 前：采购 819 total / 808 PASS / 11 SKIP；Replay 380/380；Full Java 1241 total / 1232 PASS / 9 SKIP / 0 FAIL；package 成功；5/5 frozen fixtures 未变。

修复后最终 run `v0-20261003-214519-cf9a354c`：

| 阶段 | Total | PASS | FAIL / ERROR | SKIP |
|---|---:|---:|---:|---:|
| Procurement | 820 | 809 | 0 / 0 | 11 |
| Replay | 380 | 380 | 0 / 0 | 0 |
| Full Java | 1247 | 1238 | 0 / 0 | 9 |

Package 成功，**5/5 frozen fixture fingerprints unchanged**。执行时仅临时设置 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，减小已有 fake MCP 子 JVM 启动波动，命令退出后恢复。没有开启 Legacy Live 或 PostgreSQL IT。

`JdbcProcurementCaseStorePostgresIT` 的 `schemaMigrationAlignsLegacyJsonVersionAndRejectsStaleCasWriter`、`onlyOneConcurrentWriterCanUpdateTheSameCaseVersion` 按既有 `PROCUREMENT_POSTGRES_IT=true` 门控跳过：本次未配置一次性 PostgreSQL integration-test 环境。其余 9 项是未提供历史 artifact 参数/文件的既有条件跳过，具体原因保留在归档 Surefire XML。各阶段有重叠测试，不相加为独立总数。

## 10. Smoke：失败并停止

```powershell
python -B evals/multi-agent/run.py --mode smoke --run-id multi-smoke-20261003-r1
```

计划 9×1，实际 1 个有效 trial：**0 PASS / 1 FAIL / 0 INFRA**，剩余 8 个未运行。REQ-01 的冻结 grader 结果为 MISSING_TOOL；Root 的 COMPLETED 只是运行终止状态，不是业务任务通过。

Trace 归因：Root 请求 Requisition 检查交付可行性；Child 读取正常的 20 台原申购后，把同部门另一张 8 台单据视为阻断性重复，并把尚未调查的交期与绩效作为需求不就绪。Child 返回 BLOCKED，Root 接受并停止。Case 未 Patch、Sourcing 未调用、Finalize 缺失。无 ERP 写入。

修复内容：明确领域责任，增加可验证的 Requisition blockingReasons / deferredChecks contract、原单据身份与精确重复匹配，拒绝 unsupported BLOCKED；Root 工具策略阻止跳过已确认领域阻断。不是更改 grader、任务、seed 或 expected answer。**修复后的真实模型行为尚未验证。**

## 11. Dev

**未运行 28×1**。Smoke 未通过，不能进入 Dev；无 Holdout / 40×3 / Blind Benchmark。

## 12. Routing Metrics

| Task outcome/type | Required | Forbidden | Optional |
|---|---|---|---|
| recommend | Sourcing | RFQ | Requisition |
| rfq | Sourcing、RFQ | 无 | Requisition |
| clarify / duplicate | 无 | Sourcing、RFQ | Requisition |
| blocked / bounded_failure | 无 | RFQ | Requisition、Sourcing |
| status query（仅确定性测试） | 无 | 三个 Domain | inspect |

REQ-01 实际：Requisition 1 次，Sourcing/RFQ 0 次，缺少 required Sourcing，routing error 1。Child Run technical failure 为 0，但领域判断错误导致任务失败。`unnecessary_subagent_calls` 无可靠 rubric，报告 unavailable，不伪造为 0。确定性 status query 未启动 Child。

## 13. Token / Context

下表分母不相同，**NOT_COMPARABLE**，不计算效率改善率。Phase 4 历史数据来自其归档，当前未重跑。

| 指标 | Phase 4 Dev 28×1 | Phase 6 已失败 Smoke 首例 |
|---|---:|---:|
| Task Success | 28/28 | 0/1（计划 9） |
| Input Tokens | 1,487,912 | 25,951 |
| Total Provider Tokens | 1,531,907 | 27,773 |
| Root/Supervisor agent tokens | 未分角色 | 5,704 |
| Child agent tokens | 不适用 | 21,556 |
| Guardrail tokens | 包含于总量 | 513，包含于总量 |
| Root/Supervisor peak context | 单 Agent 平均 13,825.89 | 2,494 |
| Child peak context | 不适用 | 5,335 |
| Overall max context（本例） | 不作不等分母比较 | 5,335 |

Observed provider model 为 `deepseek-flash`，没有 immutable build ID。失败提前终止造成较小上下文或较低 token 不代表效率收益。分角色 token 只计算 agent model，guardrail 不强行归属 Root/Child。修复后没有模型 token 结果。

## 14. Tool Calls / Turns / Latency

已失败 REQ-01：8 tool calls / 8 attempts / 0 tool errors；8 model turns（Root 3、Child 5）；1 subagent call；Child 6 个底层工具调用。Root 实际可见 4 个工具，Requisition 7 个；Sourcing/RFQ 未实测。

Agent latency 17,875.161 ms；包含 reset/启动/snapshot 的试验 wall clock 42.687 s。StopReason=COMPLETED。Phase 4 历史 Dev 总工具调用 310，平均 task latency 16,339.73 ms；不能与本次单个失败样本作收益比较。

## 15. Safety

本次唯一真实 trial 的 UnauthorizedWrite / DuplicateSideEffect / StaleApprovalExecution / BusinessInvariantViolation / FabricatedCriticalFact 全为 0。ERP RFQ=0，protected business hash 未变。该结论只覆盖已运行的 1 个 trial，不能替代完整 37 个 trial 的安全 Gate。

## 16. Cost 与停止边界

当前有效 trial 1/37，infra 0，没有重跑。该失败不能标为基础设施错误或悄悄替换。若要求对修复后的源码重新完成 Smoke 9 + Dev 28，总有效 trial 将为 38，需人工重新授权预算/失败替换策略；本轮不执行。

## 17. Git / Evidence

HEAD 仍为 `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，working tree dirty，Phase 6 尚未提交。用户 Handler 注释单独保留，SHA256：`8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`。未暂存、未提交、未修改其字节。

Smoke Source 是基线 HEAD + 当时未提交实现，production manifest SHA256 为 `369ae94df116de22345d72448fd82f9a9fbbc910dd514a105145f1c37b0b30a1`。Final Source production manifest SHA256 为 `fc6df7aaafc01f9f92ec40b0279fcf33cb98e223c536ebc8a247db1a4f14d2e4`，只经过离线验证。两者分别归档，不伪造最终 commit SHA。

- [Smoke 原始结果、routing 与指标](../../evals/multi-agent/reports/smoke-results.json)
- [Smoke 原始 evidence ZIP](../../evals/multi-agent/reports/smoke-evidence.zip)
- [Smoke 时源码](../../evals/multi-agent/reports/smoke-source-evidence.zip)
- [当前源码状态](../../evals/multi-agent/reports/source-state.json)
- [当前源码 evidence ZIP](../../evals/multi-agent/reports/source-evidence.zip)
- [Focused tests](../../evals/multi-agent/reports/focused-tests.json)
- [Legacy results](../../evals/multi-agent/reports/legacy-results.json)
- [归档 secret scan](../../evals/multi-agent/reports/evidence-scan.json)
- [Git status --short](../../evals/multi-agent/reports/git-status.txt)
- [分支、Tag、Handler、Fixture 与 scope 核验](../../evals/multi-agent/reports/scope-verification.json)

源码快照排除用户 Handler 的 diff，manifest 仍记录其实际文件 hash 用于审计。首次完整回归发现多余公开 Scenario factory，现已删除，未放宽装配测试；补充预算测试曾因使用大量完整 Search 响应而先触发 Context 上限，测试桩现明确设置 Child maxTurns=1 以隔离轮次预算，生产限制未降低。历史失败日志保留在 target 的独立回归目录。最终源码和 Smoke 源码是不同验证对象。

## 18. Scope

未实现 Handoff、二级 Subagent、并行 Subagent、Dynamic Tool Loading、Memory redesign、Fixed Workflow baseline。未修改冻结 grader/tasks/expected answer，也未优化 Phase 4 Context 算法。没有 commit / push / tag。停止模型评测，等待人工 Review；**Phase 6 尚未完成验收**。
