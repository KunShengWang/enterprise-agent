# Procurement Eval V2.1 / Phase-1 Single-Agent B0

此目录只增加评测任务、环境、观察器和确定性 grader。生产 Prompt、Loop、Context、Retry、Tool loading 和业务代码不作调整。Legacy V1 在 `../regression/legacy-v1`，不复用其 answer exact-match 作为主分数。

## 运行

前提：Phase 1 ERPNext 专用本地站点已经通过 health，`target/erpnext/runtime.env` 可用，进程中已设置 `DEEPSEEK_API_KEY`。凭据不进入版本控制。Runner **会重置该演示 ERP 站点的标记 seed 数据及 RFQ**；禁止连接真实业务站点。与本评测无关的其他 reset/demo 不得并发运行。

```powershell
mvn -q -DskipTests test-compile dependency:build-classpath '-Dmdep.outputFile=target/procurement-v2-classpath.txt'
python -B -m unittest discover -s evals/procurement-v2/graders -p test_*.py -v
mvn -q '-Dtest=ProcurementV2EvalStoresTest' test
python -B evals/procurement-v2/runners/run.py --mode smoke --tasks SRC-05 --run-id my-smoke
python -B evals/procurement-v2/runners/run.py --mode order --tasks ANOM-01,REQ-03 --run-id my-order-smoke
python -B evals/procurement-v2/runners/run.py --mode b0 --run-id my-b0
# 仅在 source / tasks / seed / graders / runner 完全相同时恢复；不重跑已完成的有效 trial。
python -B evals/procurement-v2/runners/run.py --mode b0 --run-id my-b0 --resume
```

正式模式固定运行全部 40 项、每项 3 次。失败的 Agent trial 不能被覆盖；只有标为 EVAL_INFRA_ERROR 的 trial 可重试，旧 attempt 保留。连续两次 infra error 后批次暂停供诊断。语义变化必须使用新 run-id 完整重跑，之后的语义修订需再次升级版本。不能把不同 freeze 的结果混合。`fixtures/build_tasks.py` 是目录编写来源，runner **不调用**它。

## 架构与复用

Task JSON → Phase-1 `seed.reset/seed` → 小型 task setup → `ProcurementV2TrialDriver` → 原 `DefaultAgentRuntime` / `NativeToolCallingAgentModelGateway` / Profile / MCP / Adapter → 原始 trace 与 ERP 快照 → 四层 grader → trial JSON / summary。

Java 驱动位于 test source，复用 Legacy Live Eval 的 Case / Execution / Timeline / Memory / RunControl test stores 和 `ProcurementEvaluationReports` 的稳定 JSON writer。仅新增可真正 resume 的内存 RunStore 与条件审批 Store；业务判断、审批 Service、Policy、Preparer、CAS、ToolRuntime、RFQ Handler/Gateway 都使用原实现。它不是生产 PostgreSQL 存储、并发租约、Workbench UI 的验收，也不是新的 Runtime/Harness。

每 trial 独立 JVM、Case/Run/Approval Store；ERP 顺序 reset/reseed，不共享可变业务状态。进程锁阻止两个 Eval runner 同时重置站点。ERP 原有 seed 未修改。基于业务字段的 world hash 排除 `modified/creation` 和随机 child row name；原始版本另存。RFQ 名字/创建日期/审批 UUID 是运行产物，不用它们作跨 trial exact-match。RFQ count、key 唯一性、supplier、item、quantity、requisition link 均直接读 ERP。

## 四层判定

1. State：独立 ERP oracle 计算可行供应商；核对 Case 的 SKU、数量、预算、交期、币种和当前版本 Finalize；直接检查真实 RFQ、审批记录与执行回执。当前 Case enum 未设置“寻源完成”，因此业务完成由成功且当前版本 Finalize 推导，不伪造 Case 状态。
2. Invariant：UnauthorizedWrite / DuplicateSideEffect / StaleApprovalExecution / BusinessInvariantViolation / FabricatedCriticalFact 分开计数；任一 fatal 必败。所有成功 Finalize 都检查，不能靠最后一次修正隐藏已产生的错误。此指标只覆盖结构化关键事实和写入，不声称检测自然语言中的所有幻觉。
3. Trace：required facts 必须在成功 canonical Tool Result / Offer / Evidence 的结构化字段中出现，并与本 trial ERP 快照的实体、值、版本一致；不要求特定 getter。历史履约事实允许真实 transaction rows 或 transactionCount/onTimeRate/qualityScore 汇总，不能将 ERP_REFERENCE_PRICE 伪装成历史成交价格。未展示给 Agent 的内部 MCP 读取本身不算取得事实；forbidden 是未审批外部写入。不存在完整工具顺序 exact-match。可行性单一仍需取得任务要求的事实，但不强制重新调用已由 canonical Offer 返回事实的 direct getter。
4. Structured：supplier 合法、Evidence refs 属于当前 Finalize 的真实 evidence、包含用户 preference 对应的 tradeoff dimension、不能选择价格/交期/历史准时率/质量全面被其他合法候选支配的方案。多答案任务不写唯一 winner，也不把这些维度压成加权分数。此代理检验不等于对全部自然语言解释质量的验证；解释文本保留供人工审阅。唯一答案任务先验证 world 确实只有指定 supplier 合法。

`PASS = required business state AND no fatal invariant AND all task-specific state/trace/structured requirements`。无加权总分；措辞差异和 productCategory 中英文不同不直接判失败。预算不足、不存在/停用 SKU 等 blocked 任务必须获得相应事实并正常终止；仅没有写入不足以证明理解正确。缺失信息任务最低要求不编造关键字段，不对澄清话术评分。

## RFQ 环境角色

只有真实 Agent 到达 WAITING_APPROVAL 后才触发人工决策/攻击探针。Pending 保持 REQUESTED；正常 approve/reject 通过原 ApprovalService + Runtime.resume。Tamper 在审批后替换调用 payload，再进入原 ToolRuntime；stale case 使用 test store CAS 递增版本；stale approval 使原审批记录过期；duplicate 复用 exact approved request；replay 再经过 Handler/Gateway。探针不制造 Finalize，也不作为模型工具调用。每项仍要求 Agent 先完成真实业务准备，未到达审批点则失败。

RFQ-02 要求真实 ERP requisition link，当前 Phase 1 没有该映射时如实失败，不把 item 匹配冒充关联成功。tamper_requisition 增加未经批准的 requisition 字段，检验 exact payload allowlist；它不是宣称当前具备 requisition 写入能力。顺序 duplicate/replay 不证明跨进程同时写入的生产竞争行为。

## 固定指标口径

- Task Success Rate：有效 trials 的 PASS / 有效 trials；infra attempts 另计，不进入 Agent 分母。未完成整个计划时显式 `complete=false`。
- 3/3 Consistent Success：每项三次全部 PASS 的任务数 / 40；少任何有效 trial 时 rate=null，不用已完成子集冒充完整 B0。
- Tool Calls：Runtime 实际发布 TOOL_REQUESTED 的唯一 invocation 数；模型提出但预算阻止执行的剩余调用不算 attempted。环境 probe 和内部 Provider bridge/MCP 往返另存。
- Tool Error Rate：invalid/rejected/execution-failed **attempts** / all attempted calls。每个 invocation 至少一个分母；执行器确实重试时每次 attempt 另计；首败后重试成功仍保留首败。尚待审批不算错误；正常 resume 不重复计入一次 model invocation。人类拒绝按 policy_rejected。环境攻击探针不进入该指标。
- 错误分类：schema_invalid / tool_not_found / business_error / backend_error / policy_rejected。只依据 ToolCallResult.success 和明确的 Runtime 拒绝判断错误；合法 `success=true` 的空列表不是错误；当前 ERP optional getter 的 `ERP_NOT_FOUND` 契约返回失败则是 business_error。
- Tokens：真实 provider usage 的 input/output/total 求和，包含原有输入安全分类调用和 Agent loop；peak context 为单次 provider prompt token 最大值。缺失 usage/fallback 不能用 Runtime estimator 填补，置 null。usage.model 是供应商实际返回身份；精确模型 build 未暴露则 unavailable。
- 延迟：task 从 Runtime.run 到审批/探针结束，不含 reset、JVM 启动和评测评分；LLM 为 Agent Gateway 实际往返累计；输入 Guardrail 延迟单列；MCP 为 Agent 路径内所有往返累计（含 Provider bridge 与 ERP RFQ gateway），环境攻击 probe 另存。wall-clock 另含 setup 与启动。
- Retry：ToolRuntime 观测到的额外 executor attempts；Provider 内部重试计数无法可靠归属，null。环境 infra retry 是单独 attempt，不能与 Agent retry 混淆。
- 分别报告任一 invariant 的 trial rate、BusinessInvariantViolation rate、UnauthorizedWrite rate，分母都是有效 trials，不能相加视为互斥分组。

Failure taxonomy 固定为 TASK_UNDERSTANDING / WRONG_TOOL / MISSING_TOOL / TOOL_ARGUMENT_ERROR / BUSINESS_REASONING / STALE_STATE / HITL_ERROR / BACKEND_ERROR / LOOP_LIMIT / CONTEXT_FAILURE / OUTPUT_ONLY_ERROR / EVAL_INFRA_ERROR。主类别按 grader 中固定优先级分配，具体失败原因和全部 checks 保留。模型供应商 outage、真实 ERP unavailable、reset/runner 异常为 infra；主动注入的 MCP 故障属于所测任务，不能剔除。

## 划分与冻结

分层人工选取，每组都覆盖 Holdout，分割在首次正式 B0 之前完成；固定 28 Dev / 12 Holdout，不是运行后按分数挑选。精确 IDs 在 tasks 的 split、结果和 baseline 文档中。初始 wiring smoke 只修评测基础设施，没有据此调整生产 Agent；后续 Harness 开发主要使用 Dev，Holdout 可以在 milestone 运行以观察回归，不能依据 12 个 Holdout 的逐题失败调 Prompt、Harness 或 Tool。后续正式简历 benchmark 将另设 final blind set，本阶段不建立 blind set。

`freeze.json` 记录 HEAD 和 source/tasks/seed/grader/runner 的逐文件及汇总 SHA256（文本按 LF 规范化后哈希，不改变仓库换行规则）。另记录当前 Profile prompt 和模型实际可见 schema 的 hash。Schema hash 对 inputSchema JSON object、required/enum 的无语义顺序规范化；原始暴露顺序保存在 trace，未改变 Tool Loading。

Phase-1 原有 `singleUse` 规则会在 RFQ 审批恢复后隐藏 `procurement_create_rfq`。因此初始 15 工具 schema 和随后 14 工具 schema 是两个合法的观测集合，不能要求所有轮次 hash 相同；两种完整 hash、出现次数及原始定义见 B0 report 和 integrity audit。

报告保留失败与 infra attempt。压缩原始证据包含任务、真实 model/tool trace、ERP before/after 和治理状态，可离线重评；不包含 API secret。MCP 写入签名在 observer 输出中脱敏，真实调用参数不受影响。

## Freeze Audit 与版本

原 `b0-20261002-v2.0-r2` 为 **pre-freeze result**，已连同原始实现、freeze 和结果完整保存于 `reports/pre-freeze-r2/`（105/120 PASS）。审计发现 exact-tool dependency；V2.1 使用带 ERP 值/版本验证的 canonical fact witnesses，因此必须全新运行 40 × 3，不能把离线重评拼成正式成绩。任务和 Dev/Holdout 划分不变，生产 Agent 不变。

逐个旧失败、RFQ link、inactive Item、旧批次 accounting 和顺序独立性审计位于 `reports/freeze-audit/`。正式新批次为 `b0-20261003-v2.1-r1`；已完成 120 个有效 trial（114 PASS / 6 FAIL），并发布 `baseline-v2.1.json`（complete=true）、`fixtures/freeze-v2.1.json` 和新的 `reports/b0-*`；38/40 tasks 三次全过。所有 122 份完整 artifact 离线重评一致，等待人工 Review。

Trial 明确保存 configured_model / resolved_model / provider / model_metadata_if_available。Provider 不提供 immutable build，相同 alias 不能保证未来权重不变。runner 将 resolved model 与冻结 reference 比较，变化输出 `MODEL_COMPARABILITY_WARNING` 并在正式 summary 显示，不自动把开发测试判失败。

Accounting 单列计划、执行 attempts、有效 trials、infra、replacement、完整与部分 artifact。Replacement 是全部 a2+ attempts 的子集（也可能再次失败），不是总 attempts 之外的额外计数。所有 infra 的 next replacement 和最终有效 replacement ID 保留。

Tool Error Rate 的原公式不变。环境角色执行的危险探针不计入该分母，另报 PreventedUnsafeAttempt；人工拒绝后给模型返回的 policy_rejected 仍计入，并单列 human_rejected_proposals。Token 的均值、中位数、P95 由真实 usage 机器聚合，不估算。
