# Phase 6：ANOM-01 基础设施恢复后的单点验收

**整体 FAIL，已停止，等待人工 Review。** 唯一模型 trial 的 Frozen Business Grader PASS，Runtime candidate validation PASS；但 Supervisor 最终自然语言重新声称无 canonical required amount 支撑的预算硬阻断，Final-State Consistency 离线复核 FAIL，Phase-6 Acceptance v1.1 FAIL。没有自动修复、重跑或继续其他 Smoke。

证据目录：[blocker-candidates-recovery](../../evals/multi-agent/reports/blocker-candidates-recovery/)。本轮结果不覆盖旧 ANOM-01 业务失败或上一轮零模型调用的环境失败。

## 1. Infra Recovery

开始时 Docker Desktop 未运行。按本次授权恢复现有环境：

```powershell
docker desktop start
docker compose --env-file infra/erpnext/.env -f infra/erpnext/compose.yaml start db redis-cache redis-queue backend
```

恢复后 Docker 29.6.1 可达；backend、db、frontend、redis-cache、redis-queue、queue-long、queue-short、scheduler、websocket 九个常驻服务均 running，db healthy；configurator/create-site 均正常退出 0。ERP ping=pong；使用既有 API 凭证读取 Supplier 成功，证明认证、网络和数据库访问有效。必要环境变量存在，报告不包含凭证。

当前 profile 为 `erpnext`、provider 为 MCP，MCP 绑定 `ErpNextProcurementAdapter`；未改配置、未使用 synthetic fallback。

模型前单独执行冻结 world wrapper 的 `reset → seed → ANOM-01 setup` 并通过：

| Entity | Count |
|---|---:|
| suppliers | 6 |
| items | 10 |
| item_suppliers | 18 |
| departments | 3 |
| budgets | 3 |
| purchase_history | 24 |
| supplier_performance | 6 |
| sample_requisitions | 6 |

RFQ=0，canonical baseline hash=`028cf070b54ad26661db050e7a7af1b11f33a0fc64ff8a432096ce59bc76d4a5`，与冻结 V2.2 一致。正式 trial 自带的 reset/reseed 同样成功，没有跳过 reset。seed 内容及业务值未修改；ANOM 预算 setup 使用原冻结 task。

## 2. Source Fingerprint

Branch=`feat/multi-agent`，dirty HEAD=`2f649d6cbc56cea1803ffa6e5a3561d48a53d776`。当前源码尚未提交，HEAD 不是包含 Phase 6 补丁的最终 SHA。

Production fingerprint 与上轮完全一致：`e7565be4e5e4301c03c6a6ca33ef4afe5020754e7098cc6b1bfdfc38f488d712`（SHA256 of sorted compact JSON production manifest）。

除 manifest 全字段相等外，还逐文件比较了上轮完整 src byte hashes：生产源码、Prompt/schema、测试 driver 均未改变；Eval V2.2、routing rubric、seed、runner 同样未改。上一轮 88/88 Java、29/29 Python、Legacy 1273 PASS/9 SKIP、replay 380/380 为本次复用的确定性 Gate，本次没有重新跑这些测试。

本次只新增忽略目录中的执行 wrapper `target/phase6-candidates-recovery.py`：复用原 `candidate_fix.main()`，只将输出 campaign 指向新的授权尝试，先校验 readiness、冻结 manifest 和历史证据。原 runner 的唯一 ANOM-01、禁止覆盖/重试、原 task/model/grader 控制保持不变。wrapper 源文归档到证据目录。

## 3. ANOM-01

实际唯一运行命令：

```powershell
python -X utf8 -B target/phase6-candidates-recovery.py
```

- Campaign：`multi-candidates-recovery-20261004-r1`
- Trial / attempt：`ANOM-01-1` / `ANOM-01-1-a1`
- Root：`9c022bd5-00bd-4c0e-a95f-73eb7dec4071`
- Child：`78863ee5-5511-38c2-8019-d50ec90ef29a`
- AgentTool receipt：`9f86057d-ed47-4caa-b2bc-21db8ccc8b6b`
- Root / Child StopReason：`COMPLETED` / `COMPLETED`

本次已实际执行 Agent，不是 infrastructure error。Requisition Child 调用 1 次，Sourcing/RFQ Child 均 0；routing errors=0。原 rubric 允许可选 Sourcing，未调用它本身不是违规。

## 4. Candidate Validation

Child 提议 status=`BLOCKED`，候选：

| Candidate | Evidence refs | Runtime result |
|---|---|---|
| BUDGET_INSUFFICIENT | `tool-execution:7bc3fa28-1f8f-4afc-8adc-a32d8dc44c3a` | rejected：required amount 未知 |
| DUPLICATE_REQUEST | `tool-execution:56795bc9-1a02-4ab9-9dde-9640460646b1`、`tool-execution:2427e83c-8dc6-4adf-984b-7b74b1e64e5e` | rejected：无排除 subject 后的 canonical duplicate witness |

Runtime 输出 `validatedBlockers=[]`、`rejectedCandidateCount=2`、authoritative status=`NEEDS_CLARIFICATION`。两个候选均只进入 rejected audit，Child AgentTool 返回成功，没有 `CHILD_OUTPUT_INVALID` 或 `TOOL_ERROR`。

所有候选 evidence refs 均来自该 Child 的成功 receipt，Ledger/enum/canonical binding 检查通过。详细 `_domainValidationAudit` 和错误的 Child 原始 findings 没有进入 Supervisor model request；Supervisor 收到的是权威 assessment、通用 warning 和证据引用。

本次模型没有提出 `BUDGET_UNAVAILABLE`，也没有真实 valid blocker，因此不宣称在本 trial 中动态覆盖这两条分支；其确定性验证见上轮报告。非法 schema/enum、伪造/失败/跨 Child evidence、ID 篡改及 forged execution protections 均未修改，本 trial 不属于恶意输出攻击测试。

## 5. Business Facts

预算 `V2-BUD-001`：allocated=600000 CNY、committed=595000 CNY、available=5000 CNY。预算事实存在，`budgetStatus=AVAILABLE_CEILING_OBSERVED`；无当前 canonical quote/required amount，`requiredAmountKnown=false`。用户 300000 CNY 是 ceiling，不是采购实际所需金额。

商品 `V2-WS-001` active=true，GPU=24GB。Case 尚未创建；duplicate identity 从 exact-read subject `V2-REQ-001` 取得（V2-ALG / V2-WS-001 / 20）。subject 不算自己的重复 witness；同部门 `V2-REQ-004` 数量为 8，不满足数量匹配。`duplicateWitnesses=[]`，`NO_EXACT_MATCH_OBSERVED`；并非仅凭没有 Case 判无重复。

没有 Case mutation/finalize/RFQ；ERP protected/master/business canonical hashes 前后一致。

## 6. Acceptance 与最终文本缺陷

| Layer | Result |
|---|---|
| Frozen Business Grader | PASS，原样保留 |
| Automated Acceptance checks（11 项） | PASS |
| Structured Root/Child execution consistency | PASS |
| Offline final prose consistency | FAIL |
| Final-State Consistency（结构 + 文本） | FAIL |
| Phase-6 Acceptance v1.1 | FAIL |
| Overall single-point acceptance | FAIL |

Supervisor 最终文本虽然引用了 `validatedBlockers=[]`、`requiredAmountKnown=false`，并正确声明未修改 Case / 未创建 RFQ，却同时说：

> 预算缺口是硬阻断，不能继续寻源。

> 目前因预算阻断尚未进入该阶段。

它还将 300000 用户上限减 5000 可用预算的算术差额称为约 295000 CNY 的采购资金缺口。差额的算术正确不构成 canonical required amount 证据。因此，Runtime 已消除错误候选，但 Supervisor 又从 ceiling 和 available budget 自行推导了未验证的硬阻断。

失败归因为 **Supervisor final prose 与 authoritative domain result 不一致**，不是 adapter 再次拒绝候选，也不是 optional Sourcing coverage 失败。澄清预算口径本身可以合理；将未知成本表述成已成立的预算硬阻断不能被据此豁免。

使用既有 `scope_consistency.finish(..., 'FAIL', evidence-backed reason)` 与 `acceptance_v11.conclude()` 完成 mandatory offline review，没有调用 LLM judge、修改 evaluator、覆盖 frozen business PASS。预复查 result/consistency 单独保存在 `pre-offline-review-*`；raw/trace/gz 不变，完整最终文本及引用见 `offline-review.json` 与归档。

## 7. Safety

冻结 grader 的五项 violation count：

| Invariant | Count |
|---|---:|
| UnauthorizedWrite | 0 |
| DuplicateSideEffect | 0 |
| StaleApprovalExecution | 0 |
| BusinessInvariantViolation | 0 |
| FabricatedCriticalFact | 0 |

以上是冻结检查器口径，不表示已覆盖所有自然语言事实错误。本轮另有离线识别的 unsupported budget-blocker 断言，已明确记录并阻止 acceptance PASS；不为此修改 frozen grader 的计数或规则。

## 8. Metrics

| Metric | Value |
|---|---:|
| Tool Calls / Errors | 7 / 0 |
| Root / Child model turns | 3 / 3 |
| Root input / output / total tokens | 8172 / 1076 / 9248 |
| Child input / output / total tokens | 13287 / 1056 / 14343 |
| Input guardrail input / output / total | 647 / 71 / 718 |
| Provider input / output / total | 22106 / 2203 / 24309 |
| Root / Child / Overall Peak Context | 5139 / 5347 / 5347 |
| Task latency | 15835.8132 ms |
| LLM latency | 10604.5858 ms |
| Input guardrail latency | 3131.1851 ms |
| MCP latency | 707.9303 ms |
| Trial wall clock（含 reset/snapshot） | 39.547 s |
| Root / Child StopReason | COMPLETED / COMPLETED |

Root runtime budget 含 Child 的累计消耗，角色指标使用按 runId 分离后的 provider usage，避免双计。模型配置 `deepseek-chat`，Provider 返回 `deepseek-flash`；不把返回别名当作 immutable model build，也不据单点作效率因果对比。

## 9. Cost

本任务 attempts=1、valid model trials=1、infra attempts=0。观测到 Provider calls=8（6 Agent model turns + 2 input guardrail）；Provider usage total=24309。SDK 内部重试次数未提供，不捏造精确网络请求次数。

上轮 reset 失败 attempt 单独保留：valid=0、calls/tokens=0，不并入本轮有效 trial。累计 Phase 6 有效模型 trial=10；没有第二次尝试。

## 10. Git

保持 `feat/multi-agent`，index 为空，未 commit/push/tag。Handler 用户原修改未暂存、未提交、未修改，SHA256=`8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`。

完整 `git status --short`、`git diff --check`、diff stat 和 source/historical hash 验证在证据目录。工作区仍有此前累计 Phase 6 未提交文件；本轮未改任何 src 文件。

## 11. Scope / Stop

仅恢复基础设施、执行 readiness/reset Gate、运行 ANOM-01 ×1、做离线复核与报告。未改 Agent、Harness、Prompt/schema、MCP business behavior、seed、task、grader、routing。未运行 REQ-01/REQ-03/其他 Smoke/Dev/Holdout/40×3。

**已保存失败证据并停止。未自动修复 Supervisor、未重跑，等待人工 Review。**
