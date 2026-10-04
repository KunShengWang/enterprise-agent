# Phase 6：Blocker Candidate 契约修复与单次环境失败

本轮生产契约修复及确定性验证完成；**模型验收未完成**。唯一授权的 ANOM-01 attempt 在 ERP reset 阶段因 Docker Desktop Linux Engine 不可用而终止，未启动 Java Agent、未调用 Provider。没有重试，也未运行其他 Smoke / Dev。等待人工 Review。

证据目录：[blocker-candidates](../../evals/multi-agent/reports/blocker-candidates/)。本报告不覆盖历史失败，不表示 Phase 6 已通过整体验收。

## 1. Root Cause 与历史保留

旧 ANOM-01（`multi-v11-20261003-r1/smoke/ANOM-01-1-a1`）仍为 business FAIL / Acceptance v1.1 FAIL，Final-State Consistency PASS。Root `bb415c83-6b57-4d27-acba-dc31947cc26b`、Child `99e9b68a-513d-3f28-a56e-c09035cf62ad`、AgentTool receipt `03356570-7a59-4491-83c6-b3abccfb239d`。

Child 声称 `BUDGET_UNAVAILABLE`、`DUPLICATE_REQUEST`，而 canonical budget 为 5000 CNY；将 subject `V2-REQ-001` 自身作为重复请求也不成立。旧 Runtime 将无事实支持的合法候选升级为整个 `CHILD_OUTPUT_INVALID`，Root 最终 `TOOL_ERROR`。旧候选枚举合法、证据引用合法，问题是候选业务含义缺乏支持，不是伪造 evidence。

完整旧 raw、trace、artifact、failure-audit、offline-review 保存在 `historical-ANOM01-failure.zip`。原有 300 个证据文件逐字节不变，清单见 `historical-preserved.json`；旧 audit 描述的是旧实现，不能当作新规则。

## 2. New Contract 与本轮源码范围

`LLM blockerCandidates → deterministic validation → Runtime authoritative domain result`。

本轮相对 `pre-source.json` 仅修改三个生产文件：

- `RequisitionAssessment.java`：验证候选结构及 own-child 成功 Evidence Ledger；独立从 canonical receipts 产生 `validatedBlockers`、`rejectedCandidates`、权威 status。
- `SubAgentExecutor.java`：向 Supervisor 返回 Runtime assessment/status；不转发可能重复错误 blocker 的原始 Requisition findings/warnings；详细审计存入 `_domainValidationAudit`。
- `AgentRegistry.java`：最小同步 candidate schema、预算语义及 Supervisor 对权威结果的读取说明；无任务 ID、5000 或特定答案提示。

其余 source 变更只有 `RequisitionAssessmentTests.java`。`source-changes.json` 记录前后哈希。Acceptance v1.1 只适配新字段、权威标记及 status/blocker 一致性，routing rubric、冻结 V2.2 grader/task/seed 未改。新增 `test_candidate_contract.py` 与禁止重复执行的 `candidate_fix.py`。

非空 validated blockers 得到 `BLOCKED`；无 blockers 且 canonical Case 完整、无预算 backend error 时为 `READY`；否则为 `NEEDS_CLARIFICATION`。模型 status 仅为提议，遗漏真实 blocker 或声称 READY 不能压过 Runtime 规则。没有新建大状态机。

## 3. Budget Semantics

- `BUDGET_UNAVAILABLE`：匹配部门的 canonical budget lookup 明确返回 `ERP_NOT_FOUND` 时，作为业务事实缺失的负向观察。它不是成功 Evidence，失败 receipt 仍不能被 candidate 冒充成功引用。有 budget record（包括 available=0）不等于 unavailable。
- `BUDGET_INSUFFICIENT`：仅在 required amount 已知、币种一致且 available 小于 required 时成立。当前实现仅接受匹配 current Case ID/version、商品、数量的成功 canonical recommendation-finalize receipt，以 unitPrice × quantity 得到 required amount。用户 budget ceiling 不是 required amount，5000 本身也不证明不足。
- timeout/auth/backend unavailable 为 `BACKEND_ERROR`，不能自动变成业务预算 blocker；未读取预算为 `UNKNOWN`。普通 Requisition-only reads 若没有当前 canonical offer，required amount 保持未知。

## 4. Duplicate Semantics

需求 identity 来源于 canonical Case 或 exact-read subject requisition。确认重复必须存在另一条 canonical requisition，部门、商品、数量匹配，排除 subject 自身及 Cancelled/Stopped，审计保留 witness IDs、完整记录与 identity。

缺少 Case 本身不决定真假：有 canonical subject 仍可查证；缺少可绑定的需求 identity 则为 UNKNOWN。相似但数量不同、其他部门、自身或关闭请求均不能成为 witness。

## 5. Rejected Candidate Handling

结构合法、引用合法但事实不支持的候选进入 `rejectedCandidates`，Child 仍返回有效领域结果；一个有效、一个不支持时只保留有效 blocker。全部不支持时，不因模型曾声称 BLOCKED 而产生权威 BLOCKED。

审计记录 type、evidenceRefs、拒绝理由、canonical witness、模型提议 status 与权威 status。Supervisor 只接收 validated blockers、权威 status、合法 evidence refs、拒绝数量及通用 warning，不承担第二次 LLM 判断。详细 `_domainValidationAudit` 不在既有 Context Projector 的 metadata 白名单中；本轮未修改 Context 算法。

## 6. Hard-fail Cases

继续拒绝 schema invalid、unknown enum、缺少结构字段、伪造 evidence、将失败 tool receipt 冒充成功 evidence、未经授权跨 Child evidence、canonical ID tampering、forged execution facts。即使候选最终会被拒绝，其 evidence 引用仍必须先通过 Ledger scope 验证。

## 7. Deterministic Tests

实际命令：

```powershell
mvn -B '-Dtest=RequisitionAssessmentTests,ChildContractBoundaryTests,RootExecutionSummaryTests,ProcurementMultiAgentTests,ProcurementCasePatchTests,ProcurementRfqHitlTests' '-Dsurefire.reportNameSuffix=phase6-candidates' test
python -X utf8 -B -m unittest discover -s evals/multi-agent -p 'test_*.py' -v
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
git diff --check
```

Legacy 运行设置 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，结束后恢复原值。运行 ID `v0-20261004-101943-28b76aed`。

| Gate | Total | PASS | Fail/Error | Skip |
|---|---:|---:|---:|---:|
| Focused Java（6 suites） | 88 | 88 | 0 | 0 |
| Offline Python | 29 | 29 | 0 | 0 |
| Legacy procurement | 820 | 809 | 0 | 11 |
| Legacy replay | 380 | 380 | 0 | 0 |
| Full Java | 1282 | 1273 | 0 | 9 |

package exit=0，5/5 frozen fixture fingerprints 不变，diff check PASS。各子集有重叠，不合并为独立测试总数。

Focused 覆盖有效/无效/混合/全部拒绝候选、预算存在/缺失/已知不足/未知金额/backend failure、当前 offer 版本/币种、重复 witness/相似/无 Case、Ledger/schema/enum、ID binding、Proposal vs Committed、Root/Child execution scope、HITL。adapter 集成测试确认错误候选不破坏 Child 结果、原始错误 findings 不进入 Supervisor、详细审计保留。

procurement 的 11 skip 包含 2 个环境门控 PostgreSQL IT（`PROCUREMENT_POSTGRES_IT=false`）及 9 个需要 artifact 参数的测试；full 默认不选择 PostgreSQL IT，其 9 skip 是 artifact 参数未提供。专门 replay 已提供参数并全部通过。逐 test 名与 JUnit skip message 见 `skip-details.json`。没有运行 Live/PostgreSQL 测试。

添加测试时出现过两次测试 fixture 问题（常量名和缺少 Child canonical lineage），修正后上述最终 Gate 全部通过；初次日志保留在 deterministic archive，没有将初次失败抹除。Python 曾因 import TestCase 重复收集，最终 29 是修正后的独立测试数。

## 8. ANOM-01 唯一模型验收尝试

```powershell
python -X utf8 -B evals/multi-agent/candidate_fix.py
```

campaign `multi-candidates-20261004-r1`，attempt `ANOM-01-1-a1`。源自 dirty HEAD `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，分支 `feat/multi-agent`；不能将 HEAD 冒充包含本轮补丁的 commit。完整 model-gate freeze 与源文件 archive 可追溯。

Production fingerprint（SHA256 of sorted compact JSON production manifest）：`e7565be4e5e4301c03c6a6ca33ef4afe5020754e7098cc6b1bfdfc38f488d712`。

reset 日志：

```text
failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine
open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.
```

结果为 `EVAL_INFRA_ERROR / EVAL_ENVIRONMENT_ERROR`，reset 在 0.578 秒内失败，未执行后续 Java Agent/Provider。business grader 未运行；runner 的 acceptance FAIL 表示 missing raw evidence，不是已运行的业务失败；Final-State Consistency UNVERIFIED。

本轮 candidates / validatedBlockers / rejectedCandidates / authoritative status、canonical budget/duplicate witnesses/evidence、Supervisor routing、Child calls、Root/Child StopReason、Tool Calls/Errors、context、Agent latency 均没有模型运行观测值。不能以 deterministic 结果或旧 ANOM 数据补作本次通过证据。仅环境 attempt wall time 为 0.578 秒。

已保存 `ANOM01-infra-attempt.zip`、`model-outcome.json`、model command log。没有 raw/trace/artifact.json.gz，因为 Agent 未启动；没有离线重标 PASS、自动修复或重试。

## 9. Safety

本次 UnauthorizedWrite、DuplicateSideEffect、StaleApprovalExecution、BusinessInvariantViolation、FabricatedCriticalFact 均为 **UNVERIFIED / Agent 未运行**。不能把空 invariant array 解读为本次五项安全验收通过。确定性 security/contract gates 已通过，历史模型安全数据继续留在历史报告中。

## 10. Cost

本任务新增 attempt=1、有效模型 trial=0、infra attempt=1。累计 Phase 6 有效 trial 仍为 9。本次 Provider requests=0、input/output/total tokens=0：依据 reset 失败后未进入 Java/Provider 调用路径，未收到 Provider usage response；不是用历史 token 数补算。

模型预算未因环境失败自动获得第二次尝试。按照失败也停止要求，本轮不启动 Docker、不重试，等待人工 Review。

## 11. Git 与证据完整性

没有 commit / push / tag / staging。Handler 用户原修改始终独立未暂存、未提交、未修改，SHA256 `8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`。

工作区包含此前累计未提交的 Phase 6 文件，不是只有本轮改动。完整 status/diff stat/check、freeze 对比、历史哈希核验与安全扫描结果在证据目录。最终 source 与 model-gate freeze 一致；尝试结束后只整理文档和证据。

## 12. Scope 与停止

未修改 frozen Eval V2.2、task、seed、routing rubric、budget/context algorithm；未运行 REQ-01、REQ-03、其他 Smoke、Dev、Holdout、40×3。未新增调度架构或下一阶段功能。仅最小 instruction/schema 同步、Production Contract 修复、确定性测试及一次基础设施失败的 ANOM-01 尝试。

**修复已通过确定性验证，模型验收仍未完成；已停止等待人工 Review。**
