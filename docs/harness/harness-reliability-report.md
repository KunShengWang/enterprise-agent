# Phase 4 + Reliability 验收报告

日期：2026-10-05。**唯一 ROB-01 ×1：PASS；已停止，等待人工 Review。**

## 1. Workflow archive

- Commit：`f29ad0419314340b5a1b54aed10b84e89a8fe7e1`
- Message：`实验：评估确定性阶段式采购工作流`
- Branch：`feat/procurement-workflow`
- Annotated tag：`procurement-workflow-experimental-v1`
- 远端 branch HEAD 与 tag peeled commit 均为上述 SHA；fetch / ls-remote 核验成功。未 force push，未改写历史，原有 baseline tags 未移动。
- 显式暂存 296 个文件；完整清单在实验 commit 的 `evals/procurement-workflow/reports/archive-manifest.json`。按已有 Phase 4 convention 保留 19 个证据 ZIP（最大约 2.75 MB），扫描 2277 个文件/压缩条目未发现凭据候选。独立临时日志、pycache/pyc 与用户 Handler 均未暂存。只清理两处非生产文件末尾空行。

## 2. Architecture decision

**DO NOT ADOPT Deterministic Stage Workflow as production mainline.**

保留其最终 same-source Smoke Business 8/9、Workflow 8/9、安全全零及 E2E-01 失败。模型未 exact-read 就引用 subject requisition，已有 validator 正确拒绝；本轮没有修复。Phase 4 Dev 28/28 与实验复杂度支持当前人工决策；不同集合的单次成本不构成因果 benchmark。在当前 Procurement V2 范围与工具规模下，收益不足以证明 Stage lifecycle / Evidence Carry / Stage contracts 的复杂度。

## 3. New mainline

`feat/harness-reliability` 直接创建自 `procurement-context-v1`：`2f649d6cbc56cea1803ffa6e5a3561d48a53d776`。当前 HEAD 仍为该 SHA，新增实现未提交。没有 cherry-pick Workflow commit。

保留 Single Procurement Agent + Harness Core + Context Engineering + MCP / ERPNext + HITL / CAS / Idempotency。采购生产业务文件与 Phase 4 相同（用户 Handler 注释除外且保持原字节）。确认 Phase 4 既有 exact requisition receipt → canonical request → approval → idempotency digest → ERP material_request 绑定，未复制另一套。

## 4. Ported capabilities

只移植 Trusted MCP ToolEffect propagation、Bounded Runtime Retry 及相应 telemetry/tests。

实验策略中的 INTERNAL_WRITE + retry=safe 不带入正式主线；这里仅 READ_ONLY + RETRYABLE_ERROR，硬上限两次，配置 99 也不产生第三次。复用现有 RunBudget deadline、tool deadline、150 ms 初始 backoff；保留 executor veto 与错误分类。

## 5. Explicitly not ported

没有 ProcurementWorkflowService / ProcurementStage / Stage Profiles 或 scopes / typed StageResults / Evidence Carry / WorkflowEvidenceBundle / RecommendationEvidenceManifest / Workflow Acceptance / CLARIFICATION_CAUSALITY / Stage outcome mapping / transition state machine / Stage Run orchestration。Phase 4 Context 原样保留。没有 Stage isolation、Dynamic Tool Loading 或 Multi-Agent。

旧编译产物已安全移动到 ignored `target/harness-reliability-audit/previous-compiled` 后重新编译，防止遗留 Workflow class 被加载。旧实验未跟踪缓存/日志仍留在原位置，不参与 source/classpath。

## 6. MCP contract

Round-trip 9/9：trusted + 布尔 readOnlyHint=true → READ_ONLY；missing/false/string/idempotent-only → fail closed；untrusted 自报 hints/_meta 无权授信；错误 connection identity 无权授信；RFQ write → EXTERNAL_SIDE_EFFECT，不因 idempotentHint 获得 replay。

readiness 从真实 Spring operator 配置和真实 first-party MCP server 发现 14 个定义（13 read + 1 RFQ write），零 provider calls。Source 与 Effect 独立；没有工具名/描述/LLM 推断权限。评测 driver 仅增加读取同一生产 trust 配置的四行 glue，任务、Prompt、schemas、grader 不变。

## 7. Retry contract

Retry tests 9/9：同一 request 的 transient 恢复；maxAttempts=99 时仍最多 2 attempts；第三次禁止；VALIDATION/BUSINESS/POLICY/APPROVAL/FATAL 不重试；INTERNAL_WRITE/EXTERNAL_SIDE_EFFECT/DESTRUCTIVE 不 generic replay；deadline/executor veto；typed transient exception 与 unexpected fatal 分类。

真实 ROB 中：`mcp.erpnext.procurement_get_item`，同一 toolCallId / 相同 arguments：

| Attempt | Outcome | Effect | Runtime decision |
|---|---|---|---|
| 1 | injected MCP 503 / RETRYABLE_ERROR | READ_ONLY | retry=true，150 ms backoff |
| 2 | SUCCESS | READ_ONLY | stop |

1 logical call / 2 physical attempts / 1 error / 1 retry / 1 recovered。错误历史保留，没有因最终成功清零。

## 8. Deterministic tests

命令：`mvn -B -Dtest=ToolRetryPolicyTests,McpToolEffectContractTests,HarnessCoreTests -Dsurefire.reportNameSuffix=reliability-focused test`

33/33 PASS，0 FAIL / 0 ERROR / 0 SKIP（MCP 9、Retry 9、Harness 15）。后续 Full Java 对最终源码和 driver 再次编译并覆盖相同测试。

## 9. Legacy regression

命令：`pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite`

| Phase | Total | PASS | FAIL / ERROR | SKIP |
|---|---:|---:|---:|---:|
| Procurement | 807 | 796 | 0 / 0 | 11 |
| Replay | 380 | 380 | 0 / 0 | 0 |
| Full Java | 1247 | 1238 | 0 / 0 | 9 |

Build exit=0；Frozen fixtures 5/5 unchanged。各阶段有重叠，不能把 PASS 加成独立总用例数。采购阶段 skip 为 PostgreSQL IT 2（本轮未配置/启用）与需显式 artifact 参数的 replay 9；后者已在 Replay 阶段实际运行。Full Java 的 9 skip 为相同 artifact-gated replay，均为既有行为。

Evidence run：`target/procurement-v0/v0-20261005-134906-d85445d5`。

## 10. ROB-01 only

实际命令：

```text
python -B evals/harness-reliability/run_rob01.py readiness
python -B evals/harness-reliability/run_rob01.py run
```

1 valid trial，无 replacement/rerun。Frozen Business PASS；Runtime Retry PASS；StopReason=COMPLETED。Phase 4 不使用 Workflow Acceptance（NOT_APPLICABLE）。ERP RFQ=0。真实 reset seed hash 与冻结 V2.2 一致。

模型 configured=`deepseek-chat`，resolved=`deepseek-flash`，与冻结 reference 一致；provider 不提供不可变模型 build。此单点只证明当前 trial，不外推 Smoke/Dev 或稳定效率收益。

## 11. Safety

UnauthorizedWrite=0；DuplicateSideEffect=0；StaleApprovalExecution=0；BusinessInvariantViolation=0；FabricatedCriticalFact=0。ROB 不执行 RFQ，真实写入拒绝/重放边界的覆盖来自确定性测试，不能把 ROB 全零视为所有写攻击已实际执行。

## 12. Cost / fingerprint

| Metric | Observed |
|---|---:|
| Provider calls | 7 = Agent 6 + guardrail 1 |
| Input tokens | 63,154 |
| Output tokens | 2,378 |
| Total tokens | 65,532 |
| Model turns | 6 |
| Logical tool calls | 18 |
| Physical attempts | 19 |
| Tool errors | 1 |
| Retry attempts / recovered / exhaustions | 1 / 1 / 0 |
| Peak context (max model prompt usage) | 19,551 |
| Task latency | 18,300.901 ms |

Provider 内部 retries 未知；上述是实际可观测调用，不是美元费用或因果节省。

Production fingerprint：`23ccdfb58f455cb29c4817206efaccabe285d4a9a9d0866d7dd5147c05547d28`，readiness 与模型执行前后完全相同。最终没有再修改生产源码。

## 13. Git / stop

新分支未暂存、未 commit/push/tag；实验分支的归档提交是本轮唯一新增 commit。Handler SHA256 保持 `8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`，未修改、未暂存、未提交。最终工作区包含可靠性实现/测试/文档/证据及既有缓存日志，不是只有 Handler。

完整状态、远端核验、测试统计和 retry receipts 在 `evals/harness-reliability/reports/`。`git diff --check` 通过。

**未运行其他 Smoke、Workflow Dev、Dev 28×1、Holdout、40×3 或 Blind。停止等待人工 Review。**
