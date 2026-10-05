# Procurement Workflow：单 Agent、确定性阶段编排

正式源码起点为 `procurement-context-v1` / `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`。本实现尚未冻结，模型验收结果见 `workflow-phase-report.md`。

## 决策与职责

采用 **Deterministic Macro Workflow + Agentic Stage Execution**。Java application service 管理合法阶段、权威 Case、硬约束校验、Recommendation Finalization 和审批发起；同一个 `PROCUREMENT` Agent 在阶段内自主选择查询、分析供应商权衡并提出结构化建议。阶段不是三个 Agent，没有 Supervisor、handoff、parent/child Run 或通用 Workflow DSL。

Phase 5 减少 schema exposure，但整体效率不足以支持默认采用。Phase 6 的跨 Agent 事实作用域和最终回答合并增加复杂度；其运行时拒绝的候选 blocker 仍可能进入 Supervisor 最终解释。两项实验保留独立分支和 experimental tag，不继承到此分支。

## 状态与迁移

唯一业务状态为既有 `ProcurementCaseStatus/ProcurementCaseState`。`ProcurementStage.from(case)` 是纯函数，不另存 WorkflowStageState。

| Canonical Case | Stage | Java 校验后 Outcome |
|---|---|---|
| 不存在、不完整、CLARIFICATION_REQUIRED | REQUISITION | NEEDS_USER_INPUT；或合法 Patch 后 SOURCING |
| SOURCING / NO_ELIGIBLE_SUPPLIER | SOURCING | NO_ELIGIBLE_SUPPLIER；或 Finalize 后 RECOMMENDATION_READY |
| RECOMMENDATION_READY | RFQ | 有本次成功 Finalize 绑定、明确用户写入意图时 WAITING_APPROVAL |

新用户输入先进入独立 Requisition assessment，针对新鲜 Case 验证修改，再根据 Case 映射推进；不会恢复旧模型思考链。状态查询直接读 Case，不启动模型。`NEEDS_USER_INPUT` 是 workflow outcome，当前阶段 Run 正常结束（COMPLETED），后续新输入创建新 Run。

## Stage Profiles

| Stage | Instructions / 任务 | 固定 Tool Scope | 结构输出 | Context / Budget |
|---|---|---|---|---|
| REQUISITION | 需求理解、真实 SKU/部门预算/原申购、重复记录调查；提出 Patch 和 blocker candidates | item/get/list、requisition/get/list、budget、history，只读 | intent、departmentId、caseChanges、subjectRequisitionId、blockerCandidates、clarifications、deferredChecks、evidenceRefs | 隔离 Run；recent user turns=1；12 turns/10 model calls/16 tools，240s |
| SOURCING | 先获得 Java Eligibility/OFFER evidence，再调查价格、交付、绩效权衡 | supplier/get/list、item-supplier、history、performance、必要 item/requisition reads、supplier_search | status、recommendation proposal、tradeoffs、riskFindings、evidenceRefs | 隔离 Run；recent=2；18 turns/16 model calls/28 tools，240s |
| RFQ | 根据 canonical Finalize 提出 RFQ 意图；原申购在当前 Run exact-read | item/get、requisition/get，只读 | requestRfq、explanation、evidenceRefs | 隔离 Run；recent=1；12 turns/10 model calls/16 tools，240s |

全部使用既有 DefaultAgentRuntime/Harness Loop。每个 Run 创建时确定 instructions、scope、schema、context policy、budget，Run 内不动态选择工具。Stage scope 是模型可见能力边界；真正安全由 ToolPolicy、Authorization、HITL、Case version、CAS 和 Idempotency 执行。

## Canonical validation 与写入

模型候选不具有权威性。成功工具记录组成当前阶段 evidence ledger；失败调用不能变成正向证据。Requisition 校验 exact item/category、部署部门预算、原申购和完整分页 duplicate scan。绑定 subject 时需要另一个匹配记录；未绑定 subject 时需要两个不同的已存记录，单条相同需求不能证明与当前讨论的需求重复。未知所需总成本不能直接确认 BUDGET_INSUFFICIENT；预算上限由 Java 取用户预算和 ERP available 的较小值。缺少关键读取时澄清停止。

Patch 复用既有 ProcurementCaseService/PatchMerger，增加 observed version 参数，拒绝基于旧 Case 的 proposal，CAS 保存；用户原 Handler 文件不参与修改。Finalize 复用既有 RecommendationFinalizer，对选中/备选/证据执行独立供应商与报价校验，再以 CAS 更新 status/version 并持久化成功记录。Java 最终回答是 canonical summary，模型 tradeoffs 单独标记为 advisory。

RFQ 使用本次 Workflow 的成功 SOURCING Finalize 引用；跨阶段引用同时绑定 PROCUREMENT 身份、阶段、workflowId、tenant、user、conversation，保持当前 Case ID/version/供应商检查。server canonical request → HITL → Approval Binding → Authorization → CAS/version → Idempotency → ERP-backed Gateway 链不变。RFQ stage 无 CREATE_RFQ 工具；Java 在阶段完成后请求审批。审批恢复复用既有 Runtime，最终外部执行状态以成功 receipt 为准。

## Context 生命周期

复用 Phase 4 ContextPolicy、ContextAssembler、ToolResultProjector 和 compaction。`isolatedRun` 只收窄历史选择及待压缩范围，不重写投影/压缩算法。下一阶段仅收到用户当前输入、新鲜 canonical Case、Java 验证结果、evidence refs 和必要 workflow metadata；不携带上一阶段完整 assistant/tool 消息。权威业务事实仍从 Case/ERP 读取，不由 conversation summary 或长期 Memory 恢复。

`Business State ≠ Conversation ≠ Run State ≠ Working Context`。workflowId/阶段元数据记录于既有 RunStore，无新 master-data store。模型输入日志、工具 receipts、审批和 RunRecord 保留审计信息。

## 评测与边界

冻结 V2.2 task/seed/world/grader 不变。独立 `evals/procurement-workflow` 增加 Workflow Acceptance：阶段顺序、schema、Case binding、固定工具集合、单一身份、无跨阶段原始上下文泄漏。先 Smoke9×1，任一失败即停；通过才 Dev28×1。没有 routing grader，没有 Holdout/40×3/Blind。

阶段 tokens/peak context 基于真实 provider usage；guardrail usage 计入全流程 provider total，单列其口径。Stage-to-stage carried context 是本地估算，不是 provider billing。单次开发对照只描述观测，不能宣称稳定因果效率收益。

## 当前限制

这是小型同步 application workflow，不提供跨进程分布式 Workflow 调度。单进程条带锁与既有 Case CAS/审批/执行幂等共同保护；阶段间进程崩溃不自动恢复整条链。已有 Case status 没有 RFQ_COMPLETE，外部完成从执行 receipt 读取。RFQ reservation 失败时采取保守停止，需要人工核对，不自动重试新 key。部署部门范围沿用 ERP 单部门配置。

stage 模型先结束、随后 Java 完成业务验证；Run 完成事件表示模型阶段完成，Workflow outcome 以最终返回、持久化 Run answer 和带 workflowOutcome 的最终事件为准。RFQ 审批另外持久化 APPROVAL_REQUIRED。新输入重新验证需求，尚不优化成跳过重复读取的 continuation。schema 验证失败不做模型重试/修复。
