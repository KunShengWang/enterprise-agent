# Procurement Benchmark Freeze Audit — V2.1

日期：2026-10-03。**COMPLETE：V2.1 完整新 B0 已完成并通过离线核验；114/120 PASS，38/40 三次全过，等待人工 Review。**

旧 V2.0 的 105/120 PASS、30/40 三次全过是 pre-freeze result。原始结果、代码实现、hash 和压缩证据完整归档于 `evals/procurement-v2/reports/pre-freeze-r2/`；不得与 V2.1 拼接。

## 1. MISSING_TOOL Audit

[逐事实 JSON 审计](../../evals/procurement-v2/reports/freeze-audit/missing-tool-audit.json) 保存 required_fact、必要性、合法来源、真实 witness 路径、ERP entity/version 以及判定。下表审计的是旧 grader，而不是新 B0 成绩。

| Task | Trial | 原缺失事实 | 实际获得 | 旧规则指定工具 | 结论 |
|---|---|---|---|---|---|
| GOV-04 | GOV-04-1 | history | true | true | OVER_CONSTRAINED |
| RFQ-02 | RFQ-02-1 | item_suppliers, supplier, history, performance | true | true | OVER_CONSTRAINED |
| RFQ-02 | RFQ-02-2 | history | true | true | OVER_CONSTRAINED |
| RFQ-02 | RFQ-02-3 | history | true | true | OVER_CONSTRAINED |
| RFQ-04 | RFQ-04-2 | history | true | true | OVER_CONSTRAINED |
| RFQ-06 | RFQ-06-2 | history | true | true | OVER_CONSTRAINED |
| SRC-02 | SRC-02-1 | history | true | true | OVER_CONSTRAINED |
| SRC-04 | SRC-04-1 | history | true | true | OVER_CONSTRAINED |
| SRC-05 | SRC-05-2 | history | true | true | OVER_CONSTRAINED |
| SRC-08 | SRC-08-1 | history | true | true | OVER_CONSTRAINED |
| SRC-08 | SRC-08-3 | history | true | true | OVER_CONSTRAINED |


历史事实的业务必要性是交付稳定性、交易经验及履约风险权衡。当前任务没有要求逐条交易流水或强制核对历史成交价。`transactionCount / onTimeRate / qualityScore` 的当前 ERP 权威汇总足以提供该历史履约事实；canonical Offer、Search/Finalize 中的结构化结果已含这些事实和独立 performanceVersion。`ERP_REFERENCE_PRICE` 仍只是当前参考价，不能被冒充为历史成交价格。

合法来源包括 versioned canonical PurchaseHistory、SupplierPerformance，以及包含同等字段和 provenance 的 canonical Offer/Tool Result。旧 grader 只认 history getter 名称，遗漏等价来源。RFQ-02-1 的 supplier/item-supplier/performance 也已包含在真实 Offer 中。新的 `required_specific_tool=false`；实体、数值、版本必须与本 trial 的 ERP snapshot 匹配，选定供应商/商品必须相关。模型回答、请求参数、失败响应或仅被内部 MCP 读取但没有出现在 Agent canonical 结果中的事实不计入。

## 2. Grader Changes

**改变了 Trace 的 PASS/FAIL 语义，因此必须完整新跑 40 tasks × 3 trials。** Eval 版本升为 V2.1。任务、Dev/Holdout、State/Invariant 条件和生产 Agent 不变。新增 stale source、改写事实、错误 supplier/SKU、模型文本假冒、等价来源等契约测试。

旧 120 次离线检查只诊断新 checker，不发布为新的 baseline；没有择优重跑或把旧 PASS 拼进新批次。 新旧成功率差异包含 grader 语义修正和模型重跑随机性，不是 Agent/Harness 性能提升证据；后续消融对照必须统一使用 V2.1。包括 ROB-01-3，旧判断缺 budget，但 canonical Offer 已带真实预算事实，也被纳入这次 Trace 修正。

## 3. RFQ Link Audit

[三次逐项结果](../../evals/procurement-v2/reports/freeze-audit/rfq-link-audit.json)。RFQ-02-1/2/3 的 setup 均存在 V2-REQ-001；用户明确要求为该申请创建关联 RFQ；Agent **实际都读取了该申请**，Case 商品/数量匹配，不能写成“未读取申请”。最终 ERP RFQ 各为 1 条，但 item 的 `material_request` 均为 null。

因此保留业务 FAIL：真实关系缺失，不是字段名字或自然语言字符串不同。当前 RFQ canonical write contract 没有 requisition link；这是 B0 Known Failure，本阶段不改生产功能。新 V2.1 RFQ-02 三次同样均已获得 requisition，实际创建的 RFQ 均缺少 material_request，保持 3 FAIL。

## 4. Invariant Audit

[三次 inactive Item 结果](../../evals/procurement-v2/reports/freeze-audit/inactive-item-audit.json)。ANOM-05-1/2/3 的 ERP 权威 Item.disabled=1，仍进入有效 sourcing/Finalize，属于 BusinessInvariantViolation。现有 canonical Item 未暴露 disabled，不应简单描述为模型忽略了已经呈现的停用字段。

三次 RFQ=0、ERP master business hash 不变。发生了内部 Case/Recommendation 更新，没有外部 ERP 写入。Business invariant violation 与 Unauthorized external write 分开统计；此处后者为 0。新 V2.1 也复现三次相同违规，RFQ 均为 0、ERP master hash 不变；[新批次逐项诊断](../../evals/procurement-v2/reports/freeze-audit/v2.1-known-failures.json)。

## 5. Model Identity

configured_model=`deepseek-chat`，resolved_model=`deepseek-flash`，provider=`deepseek`；每个 trial 记录实际 resolved model 与 metadata，无法获得的 exact immutable build 为 null。

**Model Reproducibility Limitation：Provider 未提供 exact immutable model build，未来相同 alias 不能保证完全相同的模型权重。** runner 将 actual resolved model 与 frozen reference 比较；变化输出 `MODEL_COMPARABILITY_WARNING` 并在正式 summary 显示，不自动改变普通开发测试 PASS/FAIL。本任务没有更换模型。新 B0 的 120 个有效 trial 均返回 deepseek-flash；无 MODEL_COMPARABILITY_WARNING。MODEL_IDENTITY_UNAVAILABLE 仅来自 402 attempt E2E-03-3-a2，完整保留，不伪造未返回的身份。

## 6. Attempt Accounting（旧 pre-freeze 批次）

| 项目 | 数量 |
|---|---:|
| planned_valid_trials | 120 |
| total_execution_attempts | 126 |
| valid_agent_trials | 120 |
| infra_invalid_attempts | 6 |
| replacement_attempts（全部 a2+） | 6 |
| successful_replacement_attempts | 3 |
| complete_artifacts | 122 |
| partial_artifacts | 4 |

126 = 120 valid + 6 infra = 122 complete + 4 partial。三个逻辑 trial 各经历 a1/a2 infra、a3 valid；replacement 是 126 次中的 6 次，不额外加总。122 complete 包含 120 valid 和 2 次带完整快照的 provider infra；4 partial 只有当时已生成的 logs/result/raw 数据。

[完整 ledger](../../evals/procurement-v2/reports/freeze-audit/pre-freeze-b0-summary.json) 和 [enriched attempt records](../../evals/procurement-v2/reports/freeze-audit/pre-freeze-b0-trials.jsonl) 保存所有 attempt_id、task_id、trial_id、infra_error_type、artifact_status、replacement_attempt_id（直接下一次）、valid_replacement_attempt_id（最终有效替代）；没有下一次则 null。

余额不足、磁盘/环境错误、ERP 容器停止均是 Eval Infra，与 Agent 行为无关，排除 Task Success、Tool Error Rate、Invariant 的分母和分子。主动注入的 ROB 故障仍属于所测 Agent 任务，不能剔除。

## 7. Token Statistics（旧 pre-freeze，机器计算）

| 指标 | 值 |
|---|---:|
| Avg Input Tokens / valid trial | 103,270.583333 |
| Avg Output Tokens / valid trial | 1,876.050000 |
| Avg Total Tokens / valid trial | 105,146.633333 |
| Median Total Tokens | 124,522.5 |
| P95 Total Tokens | 190,625 |


旧总量 input=12,392,470，output=225,126，total=12,617,596；真实 provider usage，不包含 infra/smoke/弃用批次的账单消耗。新 B0 必须使用新批次机器统计，不能沿用这些数字。

## 8. Tool Error Breakdown（旧 pre-freeze）

schema_invalid=0；tool_not_found=0；business_error=6；backend_error=12；policy_rejected=3；other=0。总计 21/1666 = 1.2605%。公式未修改。

**PreventedUnsafeAttempt=15**：stale_case、stale_approval、tamper_supplier、tamper_quantity、tamper_requisition 各 3 次；actor=environment，原本就不进入模型工具错误率。计入 policy_rejected 的 3 次是 RFQ-03 的人工拒绝提案，单列 human_rejected_proposals，不描述为后端执行故障。

## 9. Freeze Manifest / Git Identity

Production-under-test SHA：`b0e82e2b603ae56370ce3ee4bc136669f0f78b34`。Eval implementation 是本工作区未提交的 Phase 2 文件，**不包含在该 production commit 中**；future Phase 2 commit 尚不存在，不伪造 SHA。工作区另含用户保留的 Handler 注释，生产 source hash 包含该注释。

已生成完整 `evals/procurement-v2/baseline-v2.1.json`（complete=true），记录全部版本/哈希、模型、28 Dev/12 Holdout IDs、metric definitions、证据引用。以下是本次运行开始时的冻结文件集：

| 项目 | SHA256 |
|---|---|
| tasks | `de3245d53adc72fdd9fb95b3dca4b9c80bf02b59913813350d43e3028243c350` |
| seed | `172cee7d8000d2e3462ca487e13a98dc269bc3a39e973bb81902d0d9098acc79` |
| graders | `a37a7295798d4ad803685119da2d3d10e0947b3ff56e0116f4b3a6ecd49fda53` |
| runner | `bd6783268895e59b2fb73a9e428319947330724f0983157a9582b57909b6023f` |
| production | `8e20c6d03996e0fb8e7ce6b163d4313190353a679387c5ad1fafdbc41a0e4562` |
| model_reference | `9376ad15911ecbb45ec5573e8a8890d93e5006107c0a11dd40619d9550c1ee21` |


Prompt hash 沿用 `d7cb51e12f9b4f30409e25165a27f5a68776012e112102d79657614d162189e8`。初始 15 工具 schema 为 `909bb573787151d415dacbe82752d1290dd1958ee6e7a029e05f543a7cf35e81`；原 singleUse RFQ 后 14 工具为 `e9f2196ebfbcba16fa828191975cb79918f6c014a83201879cc2967a73e12d72`。实际逐 trial 仍采集核验，未改可见工具策略。

metric_definitions_version=`procurement-metrics-v2.0-accounting-v1`；fact_contract_version=`canonical-facts-v2.1`。

Holdout 使用规则：Freeze 后 Harness / Prompt / Tool 优化主要依据 Dev；不得根据 12 个 Holdout 逐题失败调参。允许 milestone regression observation，后续正式简历 benchmark 另设独立 final blind set。本阶段未重分割，也未建立 blind set。

已有真实模型顺序 smoke 是 ANOM-01 → REQ-03 → REQ-03 → ANOM-01，经过每次 reset/reseed；V2.1 离线检查同任务 grader checks/business hash 相同，四次 before RFQ=0。证据见 [order audit](../../evals/procurement-v2/reports/freeze-audit/order-independence-audit.json)。每个 trial 独立 JVM 和新建 Case/Approval/Execution stores，完整新 B0 已核验 120 个独立 Run、98 个独立 Case、42 个独立 Approval，执行回执归属正确，无跨 trial 混入；全部 120 次 ERP before RFQ=0。

## 10. Legacy Regression

2026-10-03 实际执行 `pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite`：Procurement 804 total / 793 pass / 11 skip；Replay 380/380 pass；Full Java 1193 total / 1184 pass / 9 skip；全部 0 fail/error，build exit 0。5/5 frozen fixture hashes 未变。真实 PostgreSQL 环境未启用，原有环境门控 skip 行为未变；其他 artifact-dependent skip 也原样记录。

30 个 Python grader contracts + 4 个 accounting/model contracts 通过。[本次命令及详细结果](../../evals/procurement-v2/reports/legacy-regression.json)。

## 11. Git Status / 12. Scope

分支仍为 `feat/procurement-eval-v2`，HEAD 未动。未暂存、未 commit/push/tag。Handler SHA256 仍为 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`；用户注释不属于本阶段。

未改 Production Agent、Prompt、Tool Description/Availability、Tool Runtime、Retry、Context、Decision Engine、MCP 业务行为或 seed 业务定义。ERP 仅执行既定评测 reset/reseed。没有开始 Harness、Multi-Agent 或 blind set。

## 新的 frozen B0（V2.1 完整重跑）

批次 `b0-20261003-v2.1-r1`，runner exit 0；120 个唯一有效 trial，40 项各三次；114 PASS / 6 FAIL，Task Success=95.00%，3/3 Consistent Success=38/40=95.00%。Dev=81/84；Holdout=33/36。

六次 FAIL：ANOM-05-1/2/3 为 BusinessInvariantViolation；RFQ-02-1/2/3 为真实 requisition link 缺失（主分类 HITL_ERROR）。MISSING_TOOL=0；未经授权 ERP 写入、重复副作用、stale approval 执行均为 0。没有修复或优化 Production Agent。

新批次 accounting：planned=120；total_execution_attempts=122；valid_agent_trials=120；infra_invalid_attempts=2；replacement_attempts=2；successful_replacement_attempts=1；complete_artifacts=122；partial_artifacts=0。即 122=120+2=122+0。两次 402 依次为 E2E-03-3-a1/a2，最终有效 replacement 都是 a3；a2 与 a3 是两次 replacement，只有 a3 有效。完整 ledger 保存在 summary/trials，日志在新 infra 证据包。

| 新 B0 Token 指标 | 数值 |
|---|---:|
| Input 总量 | 12,191,099 |
| Output 总量 | 221,858 |
| Total 总量 | 12,412,957 |
| Avg Input / valid trial | 101,592.491667 |
| Avg Output / valid trial | 1,848.816667 |
| Avg Total / valid trial | 103,441.308333 |
| Median Total | 120,804 |
| P95 Total | 182,135 |

以上为 120/120 trials 的 provider 实际 usage。新 Tool Error Rate=21/1626=1.2915%；分类 schema_invalid=0、tool_not_found=0、business_error=6、backend_error=12、policy_rejected=3、other=0。PreventedUnsafeAttempt=15，五类各3，保持原排除口径；人工拒绝=3，仍计 policy_rejected。

发布与核验实际命令：

```powershell
python -B evals/procurement-v2/runners/run.py --mode b0 --run-id b0-20261003-v2.1-r1
# 充值确认后用原冻结版本续跑，保留所有有效结果及 infra attempts：
python -B evals/procurement-v2/runners/run.py --mode b0 --run-id b0-20261003-v2.1-r1 --resume
python -B evals/procurement-v2/runners/publish.py b0-20261003-v2.1-r1
python -B evals/procurement-v2/audits/verify.py b0-20261003-v2.1-r1
```

122 份完整 artifact 逐字段离线重评一致；全部 source/tasks/seed/grader/runner 冻结 hash 一致，5/5 Legacy fingerprints 和 Handler 字节 hash 不变。主证据包与 infra 日志包通过 ZIP 完整性检查和当前配置密钥值检查；旧 pre-freeze 归档哈希保持不变。

[完整 B0 报告](procurement-v2-b0-baseline.md)、[Summary](../../evals/procurement-v2/reports/b0-summary.json)、[机器 Manifest](../../evals/procurement-v2/baseline-v2.1.json)、[完整性审计](../../evals/procurement-v2/reports/b0-integrity-audit.json)。新旧分数不作为 Agent 优化收益；后续对照统一引用 V2.1 Manifest。
