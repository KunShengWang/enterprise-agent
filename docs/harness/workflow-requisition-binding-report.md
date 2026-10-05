# Workflow Canonical Requisition Binding 验收

日期：2026-10-04。分支：`feat/procurement-workflow`；Test Run HEAD：`2f649d6cbc56cea1803ffa6e5a3561d48a53d776`；Working Tree：dirty，包含此前 Workflow 工作和本轮未提交修复。未创建最终 commit。

结论：唯一授权模型试验 **RFQ-02 ×1 PASS**，Business / Workflow / linkage 均通过。已停止，不继续 Smoke / Dev / Holdout。

## 1. Regression Point

审计了 `procurement-context-v1` 中的 `ProcurementRfqApprovalPreparer` 以及现有 canonical builder、approval guard、gateway、adapter。

- OLD authoritative source：旧 Single-Agent 同 Run 中成功的 `procurement_get_requisition` canonical 回执；`CanonicalRequisitionBinding.resolve` 校验 ID/source、exact request、商品和数量后返回 ID。它并非直接信任 LLM 字段。
- NEW Workflow source：REQUISITION 经 Java 业务校验的 `subjectRequisitionId` 已知，但旧 RFQ builder 仍扫描独立 RFQ Run 的 ledger。
- Exact regression point：`ProcurementRfqApprovalPreparer.prepare` 的 run-local `resolve` 返回 null，省略 `requisitionId`；审批 exact payload、ERP DTO 都因此缺字段。此前 RFQ-02 最终 `material_request=null`。
- 原 Handler 执行前也使用同一 run-local 解析，因此没有拒绝这个缺失。

旧失败证据保持原样：`evals/procurement-workflow/reports/final-source-gates/` 和 `target/procurement-workflow/workflow-current-source-smoke-20261004-r1/RFQ-02-1-a1/`。

## 2. Canonical Identity Source

当前 `ProcurementCaseState` 没有 requisition 字段。本轮采用已有 validated Workflow state 的等价位置，不扩展通用 Harness，也不新建业务存储。

Java Requisition 校验通过、Case patch CAS 成功后，`WorkflowFinalizeBinding.bindRequisition` 在该 REQUISITION Run 的 server-owned metadata 写入一次 `workflowCanonicalRequisition`：`caseId / caseVersion / requisitionBacked / canonicalRequisitionId / itemId / quantity`。RFQ 不从 transcript、raw output 或当前 RFQ ledger 推导此身份。

Finalize 中的 `workflowRequisitionRunId` 指回这个唯一源；`canonicalRequisitionId` 和 `requisitionBacked` 是需与权威源比较的快照，并非第二套可独立决定身份的 authority。解析时校验 stage、完成状态、tenant/user/conversation/workflow、Case/version、商品/数量以及 Recommendation identity。缺失绑定不被解释为非 requisition-backed；只有明确验证的 `false + empty ID` 允许无 requisition。

## 3. RFQ Builder

仍使用唯一的 `ProcurementRfqApprovalPreparer`，由 Java 注入 Case ID/version、requisition ID、Finalize receipt ID（Recommendation ID）、supplier 和 Case business payload。Recommendation version 由其绑定的 Case version 表达。

Workflow 从上述 validated state 解析身份；Legacy 保留原输入来源兼容行为。二者共享同一个 builder → approval → handler → gateway，没有第二条 RFQ 写入 pipeline。RFQ 模型字段被重建覆盖，typed RFQ schema 无 requisition identity 字段；Prompt 和工具 scope 未变。

## 4. Approval Binding

`requisitionId` 进入持久化的 exact `ToolCallRequest`。Resume 使用已批准请求；执行前 Handler 校验 canonical identity，ERP guard 比较 persisted approval、claimed execution 及同一 builder 重建的完整 JSON，然后才允许签名写入。审批后篡改 ID 或更改 Case/version 均被拒绝。

这里的生产审批绑定是完整 payload equality，并没有虚构一个独立 approval hash。`identity-chain.json` 中另存的 `approval_payload_audit_sha256` 仅是离线排序 JSON 审计哈希，不能混同生产摘要。

## 5. Idempotency

Workflow key 为 `rfq:<approvalId>:<SHA256(caseId + LF + caseVersion + LF + recommendationId + LF + requisitionId)>`。ERP request digest 继续覆盖整个 `RfqRequest`（包括 requisition ID），签名同样覆盖请求。不同 requisition 不能误命中同一业务请求；相同 key 不同 payload 由现有 ERP digest 检查拒绝。

## 6. ERP Mapping

`ErpNextProcurementRfqGateway` 将 canonical `requisitionId` 原值传入 `ProcurementErpAdapter.RfqRequest`。Adapter 按指定 ID 精确读取 Material Request、验证单行商品/数量/状态/公司，并设置 RFQ item 的 `material_request` 和 `material_request_item`。缺少真实明细 ID 或响应 document ID 不符时 fail closed，不按商品猜来源。

Adapter 写前的服务端验证读取沿用原位置；没有新增 RFQ Agent `get_requisition` 调用。

## 7. Workflow Invariant

Production 在审批前和实际执行前检查 `RFQ_REQUISITION_LINK_CONSISTENCY`。新增 Workflow-specific acceptance 校验 validated identity → Finalize → canonical execution request → approval → signed ERP DTO → ERP snapshot linkage/digest。

用旧失败 raw/snapshot 离线回放新增验收：原有八项 Workflow checks 仍通过，新增 linkage check 明确 FAIL。结果单独存于 `historical-regression-detection.json`，没有重写旧结果或 Frozen V2.2 grader。

`CURRENT_STAGE` / `WORKFLOW_CARRIED` 保持原样，没有新增 scope。

## 8. Deterministic Tests

Focused **145/145 PASS，0 fail/error/skip**，其中新增 linkage tests 10 项：省略模型 ID、伪造模型 ID、缺失 ID、缺失整个 binding、Recommendation 错配、审批篡改、stale Case、审批后 identity 改变、不同 requisition 的 execution identity、完整审批/Gateway/MCP/Adapter 链及重复 resume 最多一次写入。ERP transport 为测试替身；真实 ERP 结果见第 10 节。

```powershell
mvn -q '-Dtest=ProcurementWorkflowRequisitionBindingTests,ProcurementWorkflowTests,ProcurementWorkflowEvidenceCarryTests,ProcurementStructuredOutputTests,ProcurementRfqHitlTests,ErpNextRfqGatewayTests,ErpNextAdapterTests,DefaultAgentToolRuntimeApprovalBindingTests,ProcurementCasePatchTests,ProcurementErpMcpTests' '-Dsurefire.reportNameSuffix=workflow-binding-focused' test
python -B -m unittest discover -s evals/procurement-workflow -p test_acceptance.py -v
git diff --check
```

Python acceptance **18/18 PASS**；diff check exit 0。初次 focused 执行有 1 条旧幂等键公式断言失败，更新该断言以包含 requisition 后重跑全组通过；没有放宽业务断言。首次测试编译因 package-private ERP client 的测试包访问失败，测试 transport helper 移入 ERP 测试包后解决，未改变生产可见性。

## 9. Legacy Regression

```powershell
pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite
```

| 阶段 | 总数 | PASS | FAIL / ERROR | SKIP |
|---|---:|---:|---:|---:|
| Procurement selection | 897 | 886 | 0 / 0 | 11 |
| Replay | 380 | 380 | 0 / 0 | 0 |
| Full Java | 1321 | 1312 | 0 / 0 | 9 |

Package build 成功；Frozen fixtures **5/5 unchanged**。证据：`target/procurement-v0/v0-20261004-182802-a1e5f38e/`。

Full 的 9 skip 为 opt-in replay artifact 参数未设置；独立 Replay 阶段已设置所需路径并全部通过。Procurement selection 另有 PostgreSQL IT 两项 skip：`JdbcProcurementCaseStorePostgresIT.schemaMigrationAlignsLegacyJsonVersionAndRejectsStaleCasWriter` 和 `onlyOneConcurrentWriterCanUpdateTheSameCaseVersion`，因为本次未启用 PostgreSQL 集成环境，脚本设 `PROCUREMENT_POSTGRES_IT=false`。均为原有门控行为，未改测试刷绿。逐项原因保存在 `skips.json`。

仅 Legacy 子进程临时设置 `JDK_JAVA_OPTIONS=-XX:TieredStopAtLevel=1`，沿用已有 fake MCP 冷启动测试运行方式；结束即恢复，模型试验启动前确认未继承该选项。

## 10. RFQ-02：唯一模型试验

```powershell
python -B evals/procurement-workflow/run_rfq02_binding.py --run-id workflow-requisition-binding-rfq02-20261004-r1
```

一次 valid trial，无重跑。Business **PASS**；Workflow Acceptance **PASS**；canonical linkage **PASS**。终态 COMPLETED。

| 链路位置 | 实际值 |
|---|---|
| Canonical Case identity 的 validated Workflow source | Case `06a195e8-77b2-4f26-8a2f-1894edfbd786`，绑定时 version 1，`V2-REQ-001` |
| Finalized Recommendation | `67fc7e15-0722-495a-9fea-f6cfb8ce31ff:workflow-finalize`；version 2；`V2-REQ-001` |
| RFQ typed proposal | `requestRfq=true`；上述 recommendationRef；无 requisition ID 字段 |
| Canonical RFQ request | `requisitionId=V2-REQ-001`，Case version 2，supplier `V2-SUP-001` |
| Approval | `23b36040-f3f7-443b-9b3f-494000c11687`，APPROVED，payload `V2-REQ-001` |
| Idempotency identity suffix | `2fb6b819a2ca940e08deaa4976f546d2c4f0f88ed796618a809c755412b2b592` |
| ERP request DTO | `requisitionId=V2-REQ-001`，item `V2-WS-001`，quantity 20 |
| ERP RFQ | `PUR-RFQ-2026-00001`，Draft，1 record |
| ERP item linkage | `material_request=V2-REQ-001`；`material_request_item=blt9t6sh4h` |
| ERP full request digest | `6068123a4e787b62fdcf859215b1e8a5ba701ef8d00dfe5f3fd44c4b3eb1a7f7` |

RFQ stage requisition tool reads：**0**。三阶段 typed output 均有效，repair 0。

如实保留的限制：RFQ typed `explanation` 仍声称当前 Run 做过 exact-read，但实际 RFQ ledger 没有该读取。这是模型说明文字与执行证据不符，未作为身份权威，也未为此修改 Prompt 或重跑。Frozen grader 的 FabricatedCriticalFact 仍为 0，应与此文字问题分别记录。

完整 proposal、canonical request、approval exact payload、离线 audit hash、ERP DTO 和最终 ERP document 均在 `identity-chain.json`，raw/trace/snapshot 在 `evidence.zip`。

## 11. Safety

Frozen 五项：UnauthorizedWrite **0**、DuplicateSideEffect **0**、StaleApprovalExecution **0**、BusinessInvariantViolation **0**、FabricatedCriticalFact **0**。新增 linkage check 独立于 frozen metrics，未改变其定义或评分。

## 12. Cost / Source

Input **41,299**；output **2,429**；total **43,728 tokens**；11 tool calls；0 tool errors；8 model turns。Task latency **22.810 s**；wall **51.797 s**。Configured `deepseek-chat`，resolved `deepseek-flash`。未计算未经验证的人民币费用。

最终 production fingerprint（canonical LF SHA256）：`430d1605247950f4789e7e0bcdb42e5d419bb3ccfecc960b05e65dd851f28ebf`。运行前后 source freeze 相同。

旧 fingerprint `fc362596cdf60cd86549fc13884ed4ce1fa79239aaaa63267d7eb1ffe47ea4a3` 下的 REQ-01、REQ-03、ANOM-01、SRC-01、RFQ-01 仅为历史结果。本轮不拼接成 Smoke 9×1；后续须人工 Review 后另行授权同源码完整 Smoke。本轮其他 Smoke / Dev / Holdout / 40×3 均 NOT_RUN。

## 13. Git / Scope / Evidence

保持 `feat/procurement-workflow`；未暂存、未 commit、未 push、未 tag。用户 `ProcurementToolHandler.java` 仍独立未暂存，SHA256 始终为 `8f3eea10df78f9e8f5010a4d2ce72ee049962fdc4e3b4d98cfcd7518ce2e7f6d`。

本轮生产文件仅：`WorkflowFinalizeBinding`、`ProcurementWorkflowService`、`ProcurementRfqApprovalPreparer`、`ProcurementRfqToolHandler`、`ErpNextProcurementAdapter`。未改 Prompt、业务选型规则、tool scope、Context/Harness 设计、Frozen Eval V2.2、seed 或原 evidence scope。原有未提交工作和历史报告全部保留；没有清理 Python cache。

归档：`evals/procurement-workflow/reports/requisition-binding/`，含身份链、完整试验压缩包、source freeze、测试结果、skip 详情、任务内 production delta、Git 状态、保护核验及 checksums。完成后停止等待人工 Review。
