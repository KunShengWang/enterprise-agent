# ADR-001：采购宏观编排采用确定性 Workflow 与单 Agent Stage Profiles

状态：实验已归档；2026-10-05 人工架构 Review 决定不采用为正式主线。下文原始方案与决策保留为实验历史。

## Final decision (supersedes the original decision below)

**DO NOT ADOPT as production mainline — Deterministic Stage Workflow.**

正式主线回归 `procurement-context-v1` / `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`：Single Procurement Agent + Harness Core + Context Engineering + MCP / ERPNext + HITL / CAS / Idempotency。

Phase 4 已有 Dev 28/28；Workflow 最终 same-source Smoke 的 Business 与 Workflow Acceptance 均为 8/9，冻结安全五项均为 0，未进入 Dev。E2E-01 模型在未执行 exact canonical read 时引用 subject requisition，既有 validator 正确拒绝。本次不再修复或重跑。

当前实验没有显示明显 token 优势，Tool Calls 略增，却引入 Stage lifecycle、Evidence Carry、Stage contracts 等额外复杂度；多轮工作主要用于维护中间契约。不同集合、单次试验的成本只作为方向性观察，不作因果效率结论。在当前 Procurement V2 范围与工具规模下，收益不足以证明其复杂度；这不表示 Workflow architecture is invalid。

仅将 Trusted MCP ToolEffect propagation 与 Bounded Runtime Retry 独立移植到 Phase 4 新分支 `feat/harness-reliability`。不移植 Stage orchestration、typed Stage results、Evidence Carry、Stage-specific scopes / outcome mapping 或 Workflow Acceptance。不整体 cherry-pick 本实验。

最终证据：`docs/harness/workflow-mcp-final-source-smoke-report.md` 与 `evals/procurement-workflow/reports/mcp-final-source-smoke/`。实验 tag：`procurement-workflow-experimental-v1`。新正式分支仅授权 ROB-01 ×1，完成后等待 Review。

## 方案与证据

1. Phase 4 Single free-form Agent：正式锚点 `procurement-context-v1` / `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`，作为比较对象。Context/Tool Projection 已有确定性、Smoke、Dev 证据；单次 Dev×1 不是稳定因果效益。
2. Phase 5 Stage-aware Dynamic Tools：`procurement-dynamic-tools-experimental-v1`。Dev28/28、安全0、schema exposure 下降，20任务 input下降、8任务上升，SRC-02/SRC-05 主导增量；91,203 locally-estimated schema tokens saved 不等于 billing savings。整体 input/total/tools/turns 上升，因果结论 INCONCLUSIVE，不进入正式默认主线。原报告位于该 Tag 的 docs/harness/phase5-report.md 和 phase5-attribution-audit.md。
3. Phase 6 Supervisor Multi-Agent：`procurement-multi-agent-experimental-v1` / `31b510d29118513be263affbc2ebc5bf2d3be6f7`。实现与所有试验结果原样归档。ANOM 单点 business PASS/五项安全0，但 Supervisor 最终文本仍重引入已被运行时拒绝的预算 blocker，离线语义验收失败。参见该 Tag 的 docs/harness/phase6-anom01-infra-recovery-acceptance.md；不能描述为模型验收通过。
4. Deterministic Workflow + Single Agent Stage Profiles：Java 负责阶段/事实/写入边界，同一 Agent 在阶段内自主调查和分析。

## Decision

选择方案4，从 Phase4 tag 新建 `feat/procurement-workflow`。不 cherry-pick 两项实验，不继续修 Supervisor。复用 Case、Harness、Context、Eligibility/Finalize、HITL/CAS/Idempotency。固定阶段是 REQUISITION/SOURCING/RFQ，工具集合在创建 Run 时固定，不是 Phase5 的 Run 内 selector。

## Consequences

跨阶段只传 canonical Case、已验证摘要与证据引用，减少事实作用域和结果合并歧义。代价是可能增加启动、读取、guardrail 和模型成本；不要求 token 必然降低。应用状态权威和审批安全优先于让模型自由路由。用冻结业务 grader + Workflow Acceptance 验证，Smoke 任一失败即停；本次实现不自动提交。两项 experimental branches/tags 保留完整历史供追溯。
