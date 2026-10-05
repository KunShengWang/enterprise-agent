# Phase 4 + Harness Reliability

正式起点：`procurement-context-v1` / `2f649d6cbc56cea1803ffa6e5a3561d48a53d776`。
当前分支：`feat/harness-reliability`。保持 Single Procurement Agent + Harness Core + Context Engineering + MCP / ERPNext + HITL / CAS / Idempotency。

## Architecture decision

**DO NOT ADOPT Deterministic Stage Workflow as production mainline.**

Workflow 实验已冻结在 `procurement-workflow-experimental-v1` / `f29ad0419314340b5a1b54aed10b84e89a8fe7e1`，对应分支 `feat/procurement-workflow`，分支及 annotated tag 已推送并核验。完整 ADR 位于实验 Tag 的 `docs/adr/ADR-001-procurement-orchestration.md`。

Phase 4 已有 Dev 28/28；Workflow 最终同源码 Smoke Business 8/9、Workflow Acceptance 8/9、安全五项零违规，未进入 Dev。E2E-01 在未 exact-read 时引用 subject requisition，已有 validator 正确拒绝；不再修复。实验没有显示明显 token 优势，Tool Calls 略增，增加 Stage lifecycle、Evidence Carry 与中间 contracts 的维护成本。在当前 Procurement V2 范围与工具规模下，收益不足以证明其复杂度；不推断 Workflow 架构普遍无效，也不把不同集合的单次指标当作因果比较。

## Two ported capabilities

1. Trusted MCP ToolEffect propagation：本地 operator 对受控 `erpnext` 连接配置 `trusted-procurement=true`，并且连接 identity/prefix 匹配、远端 `readOnlyHint` 是布尔 true，才授予 READ_ONLY。缺失、false、字符串或不受信来源均 fail closed。远端 `_meta` 不能自授信任；不根据工具名、描述或 LLM 推断读取权限。Source (LOCAL/MCP) 与 Effect 正交。
2. Bounded Runtime Retry：仅 READ_ONLY + RETRYABLE_ERROR 可自动重试。硬上限 initial attempt + 1 retry，即使配置更大也最多两次。复用 RunBudget 的绝对 deadline、现有执行 deadline 与 backoff。executor veto、interrupt、deadline 不足均拒绝重试。VALIDATION/BUSINESS/POLICY/APPROVAL/FATAL 不自动重试；INTERNAL_WRITE、EXTERNAL_SIDE_EFFECT、DESTRUCTIVE 均不获得 generic replay。

ToolResult 保留逐 attempt 的 toolCallId、outcome、effect、retryDecision/reason/backoff，以及 errors、recovered、exhaustion。TOOL_COMPLETED trace 公开物理 attempts、retryCount 与逐 attempt 历史；逻辑调用仍由现有 RunBudget 计一次。恢复成功不会抹去首次错误。

RFQ 写入继续通过 Phase 4 已有 canonical request、Approval Binding、CAS、Idempotency 和 ERP Gateway。已确认 `CanonicalRequisitionBinding`、approval request、RFQ digest 与 ERP `material_request` 链存在；采购业务代码保持 Phase 4 原样。

## Explicit exclusions

未移植 ProcurementWorkflowService、ProcurementStage、Stage Profiles/scopes、typed StageResult、Workflow Evidence Carry/Bundle、RecommendationEvidenceManifest、Workflow Acceptance、CLARIFICATION_CAUSALITY、Stage outcome mapping、状态机或 Stage Run orchestration。未修改 Phase 4 ContextAssembler/ContextPolicy/BusinessStateProjection/ToolResultProjection/Compaction；未移植 Stage context isolation。

## Validation boundary

确定性入口：MCP round-trip / Retry / Harness focused tests，随后 `pwsh -NoProfile -File evals/regression/legacy-v1/run.ps1 -FullSuite`。

模型入口仅 `python -B evals/harness-reliability/run_rob01.py readiness` 和 `python -B evals/harness-reliability/run_rob01.py run`。复用冻结 Phase 4 runner、ROB-01、world 与 V2.2 business grader；不运行 Workflow Acceptance。现有 trial driver 唯一调整为把 operator 配置的 trust 传入自己创建的同一受控 MCP 连接。没有修改 Prompt、schema、任务 expected 或 grader。

本轮只授权 ROB-01 ×1；不论结果如何，结束后停止等待 Review，不启动 Smoke/Dev/Holdout/40×3/Blind。新分支不 commit/push/tag。
