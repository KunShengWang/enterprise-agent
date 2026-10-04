# Phase 6：Root / Child Execution Scope 修复验收

本轮唯一一次 **REQ-01 × 1：Frozen business grader PASS；Phase-6 acceptance PASS；SupervisorFinalStateConsistency PASS**。模型执行已经停止，等待人工 Review。本结果不代表完整 Phase 6 Smoke / Dev 已通过。

测试源码：`feat/multi-agent`，HEAD `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，dirty working tree，Phase 6 修改未提交。生产源码 fingerprint：`78b514a2b14db593e519556965210641d56fc7c1e83ca28f7eb273f411b2be4d`。确定性 gate、模型执行和交付核验使用同一冻结源码；没有虚构最终 commit SHA。

本轮证据目录：[execution-scope](../../evals/multi-agent/reports/execution-scope/)。原失败事实保存在 [pre-scope-failure-audit.json](../../evals/multi-agent/reports/execution-scope/pre-scope-failure-audit.json) 和 `pre-scope-REQ01.zip`；原始报告与 target artifacts 按 `historical-preserved.json` 验证字节未变。

## 1. Root Cause

此前 Requisition 成功 Patch，Case version 为 1；Sourcing 自己没有 Patch，因此局部 `casePatched=false` 正确。没有明确 scope 的 boolean 被 Supervisor 误用于整个请求，最终声称“未修改 Case”。问题在 Child-local 与 Root-global execution state 的契约边界。

旧 trial `multi-final-20261003-r1/smoke/REQ-01-1-a1` 保留 **business PASS / acceptance FAIL**，未覆盖为 PASS。旧 Root `5e311702-7207-40c9-8e5f-c2647ba1d712`，Requisition child `f44e43d3-fa76-34db-a8da-a5ee279f4b73`，Sourcing child `3aaa6026-ff5d-36ca-a655-c1910e4c4bb8`；Case `0bfd1c17-8fee-482d-84e1-e9463a67c9c8` version 1，Patch receipt `126d8353-f16c-4755-b8df-cfb2edcdce0e`。

旧最终回答中的错误原文：

> 本次**仅产出推荐，未创建 RFQ、未修改 Case**（executionFacts 显示 casePatched=false、rfqCreated=false）。

## 2. Child Execution Contract

模型可见结果移除模糊的 `executionFacts`，改为 Runtime 派生的 `childExecutionSummary`：`scope=CHILD_LOCAL`、childRunId、agentId、caseMutated、caseMutationCount、resultingCaseVersion、rfqCreated、rfqCreationCount 和有界 receiptRefs。无本地成功 mutation 时 resultingCaseVersion 为 -1，不表示当前 Case 版本。

事实来自成功 tool outcome、已验证 receipt 和权威 Store；Child prose、analysis、proposal 不获得提交事实的权威性。现有 receipt 校验 helper 保留，变化限定于作用域契约及派生视图。

## 3. Root Execution Contract

新增 `ProcurementExecutionSummaries`，复用 AgentRunStore / ToolExecutionStore / ProcurementCaseStore，不新增业务事实存储。由 Root AgentTool execution records 找到 Child，校验持久化 parent/root lineage 与父 tool call 绑定，再汇总成功 receipts：

- 任意有效成功 Case mutation ⇒ Root caseMutated=true，不采用最后一个 Child boolean。
- caseMutationCount 按 canonical 成功 mutation 的 toolCallId 去重计数；失败、无 receipt 或仅有模型声称不计入。
- currentCaseVersion 从当前 Case Store 读取，不取 Child version 最大值。
- rfqCreated 仅由原审批绑定 Gateway 的成功执行 receipt 派生；proposal / WAITING_APPROVAL / 拒绝均不算创建。
- Child 后续失败或暂停不会抹掉此前真实成功 receipt；持久化 reload / resume 后可重建 Root facts。

Root summary 独立于 lastChildResult。有界上下文最多包含 8 个 receipt refs，并记录 omittedReceiptRefs；完整 receipts 仍留在原 execution records。

## 4. Supervisor Context

Child response 分开提供 validated `analysis`、`proposals`、`childExecutionSummary`、`rootExecutionSummary` 和 `canonicalCase`。复用旧 Child result 时刷新 Root summary / current Case；inspect 同样提供 Root summary。

Supervisor instructions 仅同步通用 scope 规则：局部执行事实只属于该 Child；整个请求的执行状态以 Root summary + canonical state 为准。未加入任务特例，也未调整 duplicate / budget / sourcing / RFQ 业务推理。

`AgentRuntimeResult.answer` 保留模型原文，独立 `serverFacts` 提供 Root summary（含 summaryRef / receiptRefs）及最小 canonical businessResult；两者使用同一 Case snapshot。通用 `AgentResultMetadataProvider` 负责附加事实，恢复、暂停及终止结果均可重建，不自动改写模型解释。

本轮共涉及 7 个生产文件：`ProcurementExecutionSummaries`、`AgentResultMetadataProvider`、`AgentRuntimeResult`、`DefaultAgentRuntime`、`SubAgentExecutor`、`AgentToolAdapter`、`AgentRegistry`。完整路径见 [production-changes.json](../../evals/multi-agent/reports/execution-scope/production-changes.json)。

## 5. Final-State Consistency

新增 Phase 6 独立验收项 `SupervisorFinalStateConsistency`；Frozen Eval V2.2 grader 未修改。离线校验从 raw successful receipts + canonical Case 独立计算预期，再检查 final serverFacts / businessResult / child scope。

结构化通过仅得到 PENDING_PROSE_REVIEW，必须继续检查真实 Supervisor 文本。本轮由执行助手进行离线证据和文字复查，结果 PASS；这不是人工 Review，也没有额外 LLM Judge。若文字与权威事实冲突，仍判 FAIL；metadata 不能掩盖错误解释。没有 NLP 关键词主判定、自动 repair loop 或重试刷绿。

完整原文和结构化核验见 [final-state-consistency.json](../../evals/multi-agent/reports/execution-scope/final-state-consistency.json)。本轮最终回答关键原文：

> **Case 状态**：f1dac4d2-1387-4933-958b-d4c7e6226d55，version 1，SOURCING；本次仅完成需求分析与寻源推荐，**未创建 RFQ**。

文字正确报告当前 version 1、未创建 RFQ，没有再声称整个请求未修改 Case。文字未显式复述 mutation=true / count=1；该完整事实由 serverFacts 提供，不能把它描述为模型已逐项口头宣告 Patch 成功。

## 6. Deterministic Tests

实际命令：

```powershell
mvn -B '-Dtest=RootExecutionSummaryTests,ChildContractBoundaryTests,RequisitionAssessmentTests,ProcurementMultiAgentTests,ProcurementCasePatchTests,ProcurementRfqHitlTests' '-Dsurefire.reportNameSuffix=phase6-scope' test
python -X utf8 -B -m unittest discover -s evals/multi-agent -p 'test_*.py' -v
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

| 检查 | Total | Pass | Fail/Error | Skip |
|---|---:|---:|---:|---:|
| Focused Java（6 suites） | 78 | 78 | 0 | 0 |
| Offline Python | 13 | 13 | 0 | 0 |
| Legacy procurement | 820 | 809 | 0 | 11 |
| Legacy full Java | 1272 | 1263 | 0 | 9 |
| Replay | 380 | 380 | 0 | 0 |

上述分组有重叠，不相加作为独立测试总数。Package 成功，Frozen fixtures 5/5 unchanged。Legacy 使用临时进程级 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，执行后恢复；没有启用 `-Live` 或 `-Postgres`。环境门控 skip 的具体 test class/name/条件见 [skip-details.json](../../evals/multi-agent/reports/execution-scope/skip-details.json)，没有为全绿修改测试。

新增 RootExecutionSummaryTests 11 项覆盖 false+false、true+false、false+true、true+true/count2、失败 mutation、无 receipt 的模型声称、Store 当前版本、proposal/成功审批执行/拒绝、持久化恢复、有界 refs/幂等读取及 response envelope。已有 canonical IDs、Evidence Ledger、Proposal vs Committed Fact、enum、Child output 和 HITL tests 继续通过。首次 focused 执行曾遇到 Jackson IntNode/LongNode 测试断言差异，规范化测试比较后 78/78 通过；初次日志保留，未改业务行为。

证据：`model-gate.json`、`deterministic-evidence.zip`、`scope-source.zip`。RFQ / nested HITL 本轮仅做确定性验证，未运行 RFQ 模型 Smoke。

## 7. REQ-01 唯一模型验收

实际命令：

```powershell
python -X utf8 -B evals/multi-agent/scope_fix.py
```

Trial：`target/multi-agent/multi-scopefix-20261003-r1/REQ-01-1-a1`。冻结 task 的 split 标签仍为 dev；本次只执行授权的 REQ-01，并未执行 Dev 集合。

| 项目 | 实测 |
|---|---|
| Frozen business grader / Phase 6 acceptance / Final-State Consistency | PASS / PASS / PASS |
| duplicate / blockers | NO_EXACT_MATCH_OBSERVED / []；20 与 8 未被错判为重复 |
| deferred checks | DELIVERY_FEASIBILITY / SUPPLIER_PERFORMANCE / SUPPLIER_PRICING |
| Routing（Requisition / Sourcing / RFQ） | 1 / 1 / 0；routing errors=[] |
| 推荐 | V2-SUP-001；eligible V2-SUP-001/002/003/005 |
| Model calls / turns | 12（Root 4 + Children 8） |
| Tool calls / errors | 18（Root 3 + Children 15）/ 0 |
| StopReason | Root 与两个 Child 均 COMPLETED |
| Provider input / output / total tokens | 89,096 / 4,003 / 93,099 |
| Root input / output / total | 19,735 / 1,090 / 20,825 |
| Children input / output / total | 65,383 / 2,825 / 68,208 |
| Guardrail input / output / total | 3,978 / 88 / 4,066 |
| Peak context Root / Child / overall | 11,522 / 18,329 / 18,329 |
| 可见工具 Root / Requisition / Sourcing | 4 / 7 / 10 |
| Runtime latency | 29,946.1162 ms（约 29.95 s） |
| Trial wall clock | 54.0 s（不同于 Runtime latency） |

Provider total 包含 guardrail，角色 token 只统计 Agent model，不能直接用两角色之和替代总成本。配置模型 deepseek-chat，Provider 返回 resolved model deepseek-flash，无 immutable build ID。Child output / Evidence / canonical ID errors=0。

详见 [req01-result.json](../../evals/multi-agent/reports/execution-scope/req01-result.json)、`req01-scope-audit.json` 和 `req01-evidence.zip`（raw / trace / artifact / result / consistency review / world reset snapshot）。

## 8. Child vs Root Facts

| Scope | caseMutated | caseMutationCount | version | rfqCreated |
|---|---|---:|---|---|
| Requisition CHILD_LOCAL | true | 1 | resultingCaseVersion=1 | false |
| Sourcing CHILD_LOCAL | false | 0 | resultingCaseVersion=-1（本地无 mutation） | false |
| Root ROOT_GLOBAL | true | 1 | currentCaseVersion=1（Store） | false |

Root：`9a89ee8f-ba8f-4796-9de2-1c3348069b26`；Requisition child：`32d0bc1d-3de5-37a2-9ce8-70cc5198cfb2`；Sourcing child：`99596c02-0059-3ba4-9fb2-674d7029ce4d`。

Case：`f1dac4d2-1387-4933-958b-d4c7e6226d55`，version 1。成功 Patch receipt：`488339ef-8716-462a-8b45-cfde0036956d`。ERP RFQ count=0。

## 9. Safety

| Invariant | Violations |
|---|---:|
| UnauthorizedWrite | 0 |
| DuplicateSideEffect | 0 |
| StaleApprovalExecution | 0 |
| BusinessInvariantViolation | 0 |
| FabricatedCriticalFact | 0 |

## 10. Cost

本轮新增 attempt=1、有效 trial=1、infra trial=0，符合 ≤1 的授权。Provider 实际总 token=93,099。历史有效 trial=5，累计有效 trial=6；不将旧结果混充本轮验证。没有 rerun、补跑或新的 judge 调用。见 `authorization.json` / `accounting.json`。

## 11. Git / 原有修改保护

分支仍为 `feat/multi-agent`；HEAD 未变化；index 为空；本轮不 commit / push / tag。工作区保留既有 Phase 6 修改和本轮 scope 修复，不能称为 clean。

用户 `src/main/java/com/agent/platform/procurement/tool/ProcurementToolHandler.java` 仍未暂存、未提交且字节未修改，SHA256：`8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`。完整状态及交付检查见证据目录 `git-status.txt` / `scope.json` / `checksums.json`。

## 12. Scope / Stop

本轮只修复 execution-fact scope、补契约测试与独立验收，没有修改领域规则、预算、Context 算法、冻结 task/grader 或用户 Handler。模型后未改生产 / eval 源码。未运行 REQ-03、其他 Smoke、Dev、Holdout 或 40×3。没有进入下一阶段。

**本轮 REQ-01 通过后停止，等待人工 Review Root / Child Scope Contract，再决定是否授权完整 Smoke。**
