# Procurement Eval V2.1 — Phase-1 Single-Agent B0

日期：2026-10-03。**COMPLETE — 40 tasks × 3 trials = 120 个有效 trial；114 PASS / 6 FAIL。** 新批次 `b0-20261003-v2.1-r1` 已完成、封装和离线全量核验，等待人工 Review；未 commit/push/tag。

V2.0 的 105/120 为 pre-freeze 历史结果，原实现与原始证据保存在 [旧结果归档](../../evals/procurement-v2/reports/pre-freeze-r2/archive-manifest.json)。V2.1 修正 Trace exact-tool dependency，重新执行完整 120 次，没有复用或拼接旧 PASS。两版分数差异不能作为 Agent/Harness 性能提升证据。

## 代码、模型与边界

- Production-under-test SHA：`b0e82e2b603ae56370ce3ee4bc136669f0f78b34`。
- Eval implementation：分支 `feat/procurement-eval-v2` 上未提交的 Phase 2 文件，**不在该 production commit 中**。
- Working tree：dirty；用户 Handler 注释原样保留，未暂存、未提交。
- configured_model=`deepseek-chat`；resolved_model=`deepseek-flash`；provider=`deepseek`；temperature=0.2、maxTokens=4096。
- Exact immutable model build：unavailable/null。Provider 没有提供不可变 build，未来相同 alias 不保证相同权重。
- 比较警告：`['MODEL_IDENTITY_UNAVAILABLE']`；仅 E2E-03-3-a2 的 402 infra attempt 缺少实际模型身份；120 个有效 trial 全部为 deepseek-flash，无 MODEL_COMPARABILITY_WARNING。

复用原 Single Agent、Runtime、ContextManager、Profile、ToolRegistry、Policy、ApprovalService、Preparer、ToolRuntime、Handler、ERP Gateway、MCP、Adapter；治理 stores 为 test-only 内存实现，ERPNext 为真实本地演示服务。每个 trial 独立 JVM，ERP 顺序 reset/reseed。这不证明 PostgreSQL 持久化、分布式租约、并发写入或 Workbench UI 已验收。

原 Runtime 的 24 turns / 20 model calls / 40 tool calls / 384000 input / 16000 output / 240000 ms 及工具最大 attempts=2 保持不变。历史 Phase-1 Demo 的 28 tool calls 仅是一次观测，不与本 B0 当作配对性能样本。

## 任务与 Holdout

固定 40 tasks，28 Dev / 12 Holdout；没有按结果重新划分。

Dev：`ANOM-01, ANOM-03, ANOM-04, ANOM-06, E2E-01, E2E-03, E2E-04, E2E-06, GOV-01, GOV-02, GOV-03, REQ-01, REQ-03, REQ-04, REQ-06, RFQ-01, RFQ-02, RFQ-04, RFQ-05, ROB-01, ROB-02, ROB-03, SRC-01, SRC-02, SRC-03, SRC-04, SRC-05, SRC-07`。

Holdout：`ANOM-02, ANOM-05, E2E-02, E2E-05, GOV-04, REQ-02, REQ-05, RFQ-03, RFQ-06, ROB-04, SRC-06, SRC-08`。

Freeze 后 Harness / Prompt / Tool 优化主要依据 Dev，不得针对 12 个 Holdout 的逐题失败调参。Holdout 可在 milestone 用作 regression observation；最终正式 benchmark 后续另建独立 final blind set，本阶段未创建。

## 评分与指标

完整定义见 [Eval README](../../evals/procurement-v2/README.md) 和 [Freeze Audit](procurement-v2-freeze-audit.md)。PASS 需要 State、Invariant、Trace、Structured 全部成立。Trace 认可真实 canonical Tool Result / Offer 中与本 trial ERP 实体、值、版本匹配的必要事实，不要求指定 getter；模型 prose/arguments、过期或不相关事实不计。历史履约汇总不是历史成交价。

严重业务不变量、未授权外部写入分别统计；环境安全探针与 Agent 工具错误分别统计。余额、环境和容器错误排除 Agent 分母，主动注入的鲁棒性故障仍计入所测任务。Tokens 仅来自 provider usage，不用 estimator 补齐；指标覆盖数保留在 summary。

## 完整 B0 结果

| 指标 | 结果 |
| --- | --- |
| Task Success | 114/120 = 95.0000% |
| 3/3 Consistent Success | 38/40 = 95.0000% |
| dev | 81/84 = 96.4286% |
| holdout | 33/36 = 91.6667% |
| UnauthorizedWrite | 0/120 |
| DuplicateSideEffect | 0/120 |
| StaleApprovalExecution | 0/120 |
| BusinessInvariantViolation | 3/120 |
| FabricatedCriticalFact | 0/120 |
| Tool Error Rate | 21/1626 = 1.2915% |
| Invalid Tool Calls | 0 |

| 类型 | Tasks | Trials | Pass | Fail |
| --- | --- | --- | --- | --- |
| ANOM | 6 | 18 | 15 | 3 |
| E2E | 6 | 18 | 18 | 0 |
| GOV | 4 | 12 | 12 | 0 |
| REQ | 6 | 18 | 18 | 0 |
| RFQ | 6 | 18 | 15 | 3 |
| ROB | 4 | 12 | 12 | 0 |
| SRC | 8 | 24 | 24 | 0 |

## Attempt / Trial / Artifact 对账

| 指标 | 数量 |
| --- | --- |
| planned_valid_trials | 120 |
| total_execution_attempts | 122 |
| valid_agent_trials | 120 |
| infra_invalid_attempts | 2 |
| replacement_attempts | 2 |
| successful_replacement_attempts | 1 |
| complete_artifacts | 122 |
| partial_artifacts | 0 |

122 attempts = 120 valid + 2 infra = 122 complete artifacts + 0 partial artifacts。replacement 是 a2+ attempts 的子集，不额外加总。

| Infra attempt | 原因 | Artifact | 下一 attempt | 最终有效 replacement |
| --- | --- | --- | --- | --- |
| E2E-03-3-a1 | MODEL_PAYMENT_REQUIRED | complete | E2E-03-3-a2 | E2E-03-3-a3 |
| E2E-03-3-a2 | MODEL_PAYMENT_REQUIRED | complete | E2E-03-3-a3 | E2E-03-3-a3 |

## Token / Context / 延迟

| 指标 | 有效数据 trials | 总量 | 平均 / trial | 中位数 | P95 |
| --- | --- | --- | --- | --- | --- |
| tool_calls | 120 | 1,626 | 13.55 | 17 | 20 |
| input_tokens | 120 | 12,191,099 | 101,592.491667 | 118,601 | 179,615 |
| output_tokens | 120 | 221,858 | 1,848.816667 | 2,004 | 2,792 |
| total_tokens | 120 | 12,412,957 | 103,441.308333 | 120,804 | 182,135 |
| peak_context_tokens | 120 | 2,283,037 | 19,025.308333 | 21,603.5 | 29,325 |
| task_latency_ms | 120 | 2,171,428.411896 | 18,095.236766 | 19,270.187949 | 25,762.861 |
| llm_latency_ms | 120 | 1,397,102.319 | 11,642.519325 | 11,918.2201 | 17,854.0815 |
| input_guardrail_latency_ms | 120 | 339,432.616602 | 2,828.605138 | 2,683.80665 | 3,393.7589 |
| mcp_latency_ms | 120 | 242,532.578822 | 2,021.104824 | 2,528.957202 | 3,095.61 |
| tool_retry_count | 120 | 0 | 0 | 0 | 0 |

延迟单位为 ms。task 不包含 reset、JVM 启动和 grader；LLM、input guardrail、MCP 分别计量。Provider 内部 retry 无可靠计数，保留 null。Token 总量只含有效 B0 trials，不代表 infra、smoke、历史批次在内的总账单。

## Tool Error / 安全观测

| Category | 次数 |
| --- | --- |
| schema_invalid | 0 |
| tool_not_found | 0 |
| business_error | 6 |
| backend_error | 12 |
| policy_rejected | 3 |
| other | 0 |

PreventedUnsafeAttempt=15，actor=environment，不进入 Tool Error Rate；各类为 `{'stale_approval': 3, 'stale_case': 3, 'tamper_quantity': 3, 'tamper_requisition': 3, 'tamper_supplier': 3}`。人工拒绝 proposal=3，仍按原口径计入 policy_rejected，单独呈现，不归为后端执行故障。

## B0 Known Failure

| Trial | 主类别 | 原因 | Trace ID |
| --- | --- | --- | --- |
| ANOM-05-1 | BUSINESS_REASONING | unexpected valid recommendation for blocked/incomplete task; invalid item entered valid sourcing; BusinessInvariantViolation | 233490d5-c16a-4f00-94b0-0ecc576bc24d |
| ANOM-05-2 | BUSINESS_REASONING | unexpected valid recommendation for blocked/incomplete task; invalid item entered valid sourcing; BusinessInvariantViolation | 73bf569e-0913-4a49-a356-08746dfd6437 |
| ANOM-05-3 | BUSINESS_REASONING | unexpected valid recommendation for blocked/incomplete task; invalid item entered valid sourcing; BusinessInvariantViolation | f5f29aa9-f7bf-465c-97cf-8ed4fa3ab0d3 |
| RFQ-02-1 | HITL_ERROR | ERP RFQ lacks correct requisition link | 252968d9-10b0-468c-b294-45cce178dbef |
| RFQ-02-2 | HITL_ERROR | ERP RFQ lacks correct requisition link | b9491b91-a346-4cbf-b840-cc4c857eca0b |
| RFQ-02-3 | HITL_ERROR | ERP RFQ lacks correct requisition link | 7a3eaa2a-5b8b-4134-aad4-608436fe9701 |

ANOM-05：ERP 权威 Item.disabled=1，仍发生有效 sourcing/Finalize；canonical Item 未暴露 disabled，不能简单归咎模型忽略已呈现字段。内部 Case/Recommendation 更新与未授权 ERP 写入不同，具体 RFQ 数见证据。RFQ-02：任务要求真实 requisition link，当前 canonical RFQ write contract 缺少该映射；不以 item/quantity 匹配替代关系。两项保持生产原状，不在本阶段修复。新 RFQ-02 三次均实际取得 requisition，ERP 各有一条 RFQ 但 material_request 为 null；ANOM-05 三次均 RFQ=0、ERP 主数据 hash 未变。[逐项诊断](../../evals/procurement-v2/reports/freeze-audit/v2.1-known-failures.json)。

## 冻结 Manifest

[机器 Manifest](../../evals/procurement-v2/baseline-v2.1.json)；`eval_version=procurement-eval-v2.1`，`metric_definitions_version=procurement-metrics-v2.0-accounting-v1`，`fact_contract_version=canonical-facts-v2.1`。

| 项目 | SHA256 |
| --- | --- |
| task_hash | `de3245d53adc72fdd9fb95b3dca4b9c80bf02b59913813350d43e3028243c350` |
| grader_hash | `a37a7295798d4ad803685119da2d3d10e0947b3ff56e0116f4b3a6ecd49fda53` |
| runner_hash | `bd6783268895e59b2fb73a9e428319947330724f0983157a9582b57909b6023f` |
| seed_hash | `172cee7d8000d2e3462ca487e13a98dc269bc3a39e973bb81902d0d9098acc79` |
| prompt_hash | `d7cb51e12f9b4f30409e25165a27f5a68776012e112102d79657614d162189e8` |
| production_working_tree_hash | `8e20c6d03996e0fb8e7ce6b163d4313190353a679387c5ad1fafdbc41a0e4562` |
| tool_schema_hash | `909bb573787151d415dacbe82752d1290dd1958ee6e7a029e05f543a7cf35e81` |
| tool_schema_hash | `e9f2196ebfbcba16fa828191975cb79918f6c014a83201879cc2967a73e12d72` |
| archive_sha256 | `e52bec1b27b80341c47d8ab08ede3997f219e6763f06e478bdb5dba9e81d9a69` |

初始 15 工具与原生 singleUse RFQ 后 14 工具的两种 schema 均为合法观测。文本文件集 hash 规范化 LF 后计算，不修改仓库换行规则。

## 执行与可复查证据

```powershell
python -B evals/procurement-v2/runners/run.py --mode b0 --run-id b0-20261003-v2.1-r1
$evalTemp=(Resolve-Path 'target/procurement-v2/jvm-temp').Path
$env:TEMP=$evalTemp
$env:TMP=$evalTemp
python -B evals/procurement-v2/runners/run.py --mode b0 --run-id b0-20261003-v2.1-r1 --resume
python -B evals/procurement-v2/runners/publish.py b0-20261003-v2.1-r1
python -B evals/procurement-v2/audits/verify.py b0-20261003-v2.1-r1
```

[Summary](../../evals/procurement-v2/reports/b0-summary.json)、[Attempts](../../evals/procurement-v2/reports/b0-trials.jsonl)、[证据包](../../evals/procurement-v2/reports/b0-evidence.zip)、[索引](../../evals/procurement-v2/reports/b0-evidence-index.json)、[完整性审计](../../evals/procurement-v2/reports/b0-integrity-audit.json)。完整 artifact 重评 122 份全部一致；120 个有效 trial、40 项各三次，ERP before RFQ 全为 0，Run/Case/Approval/执行归属无跨 trial 混入。

顺序 smoke `ANOM-01 → REQ-03 → REQ-03 → ANOM-01` 经逐次 reset/reseed 后，同任务评分检查与业务 hash 一致；[顺序审计](../../evals/procurement-v2/reports/freeze-audit/order-independence-audit.json) 保存证据。

[Infra 日志证据包](../../evals/procurement-v2/reports/b0-infra-evidence.zip) 和 [索引](../../evals/procurement-v2/reports/b0-infra-evidence-index.json) 保留两次 402；[归档检查](../../evals/procurement-v2/reports/freeze-audit/v2.1-archive-safety.json) 确认主包与 infra 包完整，且未包含当前配置密钥值。旧 pre-freeze 归档哈希未变。

## 测试、Git 与 Scope

`pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite` 已通过：Procurement 804 total / 793 pass / 11 skip；Replay 380/380；Full Java 1193 total / 1184 pass / 9 skip；全部 0 fail/error，build exit 0，5/5 frozen fingerprints 未变。真实 PostgreSQL 环境未启用，原有环境门控 skip 保留。30 个 Python grader tests、4 个 accounting/model tests 通过；[详细命令与结果](../../evals/procurement-v2/reports/legacy-regression.json)。

用户 Handler 字节 SHA256 仍为 `8F3EEA10DF78F9E8F5010A4D2CE72EE049962FDC4E3B4D98CFCD7518CE2E7F6D`，未暂存、未提交、未修改。没有优化 Production Agent、Prompt、Tool Loading/Description/Availability、Runtime、Retry、Context、Decision Engine、MCP 业务行为；没有开始 Harness、Multi-Agent 或 final blind set。
