# Phase 5：Stage-Aware Dynamic Tool Loading

基于 `procurement-context-v1` (`2f649d6cbc56cea1803ffa6e5a3561d48a53d776`)。仅 ERPNext Procurement profile 启用，Legacy profile 保持原行为。系统仍是 Single Agent；十几个工具适合确定性规则，不需要 embedding、向量索引、额外 LLM 选工具调用或 Tool Search。

## 编码前审计

`evals/dynamic-tool-loading/reports/pre-change-tool-audit.json` 保存冻结 Phase 4 请求中的实际 15 个 ToolDefinition，并与 ToolCatalog、MCP service、CaseService 和 RFQ ApprovalPreparer 对照。初始 4 个本地工具 + 11 个 MCP read；RFQ singleUse 消耗后为 14。原 exposure 对 Case 状态不作筛选，execution prerequisite 仍由业务层验证。完整逐工具分类见 Phase 5 report。

ERP MCP registry metadata 没有 readOnly；本阶段不增加该字段，因为它还会改变 Phase 4 的结果去重行为。集中规则表依据已核验的 MCP service read contract 分类，并检查 provider/server/tool binding 和存在的 effect metadata，不修改名称、描述或 schema。

## 唯一集成路径

`ToolRegistry → profile allowlist / 既有 visibility / singleUse → ToolSelector → Selected ToolDefinitions → ContextAssembler → 原 ModelGateway`。

Selector 接收 profile identity、服务端 Case snapshot、RunRecord（含 pending approval/usedTools）、同 run ToolExecutionRecord 和 registry candidates；返回原始 ToolDefinition 对象的子集。Runtime 不散落工具名称规则，不建立新 Model Request pipeline。

Case snapshot 经 tenant/user/conversation scope 从现有 CaseStore 新鲜读取；零个或多个 provider、缺少身份等情况 fail safe，存储错误显式失败。它与后续 canonical context projection 是两次读取，并非原子快照；执行时仍由现有 policy、CAS 等再次校验。模型文本或请求 metadata 中的 stage 不能授权 RFQ exposure。

## 阶段规则和 Tool Matrix

以下为计算出来的 exposure label，不持久化，不增加第二套业务状态机。当前 Case Patch 真实状态为 CLARIFICATION_REQUIRED / SOURCING；枚举中的 RECOMMENDATION_READY 不能单独授予 RFQ。

Core = MCP get_requisition、get_item、get_budget_status、get_rfq（完整前缀均为 `mcp.erpnext.procurement_`）。四个工具用于跨阶段预算、申购、商品和 RFQ 查询，不含业务写入。

| Business / Run State | Visible Tools（完整 registry 下） | Hidden Tools | Why |
|---|---|---|---|
| 已验证无 Case / CLARIFICATION_REQUIRED | 12：11 MCP reads + Case Patch | Search、Finalize、RFQ | 保留既有 Prompt 在 Patch 前读取供应商/history/performance 的依赖；不改 Prompt |
| 完整 Case，尚无当前版本 Finalize | 13：Core、Patch、Search、Finalize、item/supplier discovery、supplier/history/performance reads | RFQ、list_requisitions | 进入已有 Case 的寻源，尚不能提议写入 |
| 当前 run 成功 Finalize，Case/version 匹配且 RFQ 字段齐备 | 8：Core、Patch、list_items、get_supplier、RFQ | 其他 discovery/Search/Finalize/history/performance | 将当前推荐推进到审批提议；仍允许修需求（下轮重新判定） |
| WAITING_APPROVAL / pendingToolCall | 4：Core | 所有非 Core | 待审批停止期间不继续 sourcing 或重复提议 |
| RFQ singleUse 已消耗，运行中恢复 | 5：Core、get_supplier | 其他工具，包括 RFQ | 查询写入结果，不重新暴露已消费写工具 |
| unknown / missing / invalid Case state | 4：有效 Core | 所有非 Core | 无权威状态时收紧为只读；不 fallback 全集 |
| 错误 Agent identity 却启用 selector | 0 | 全部 | 显式配置错误不能扩大能力 |

表中数量是完整候选集上限；registry 缺失/不合法 contract、profile allowlist、singleUse 会进一步缩小。跨阶段合法预算查询由 Core 支持，不保证所有历史寻源工具永远可用。

正常 WAITING_APPROVAL 会停止模型循环，因此模型 trace 不一定出现 PENDING_APPROVAL 阶段；该规则由确定性测试验证。真实审批恢复后的 POST_RFQ exposure 则可从模型请求核验，不能把两种验证方式混同。

RFQ exposure 额外检查最新同 run SUCCEEDED Finalize、result.success、verified-provider-snapshot、Case ID/version、recommendedSupplier 与 selectedOffer supplier 一致。失败、旧版本、其他 run、无法解析的 Finalize 均不能打开 RFQ。

## Selector 与安全策略

Selector 负责 schema exposure，不代替 ToolPolicy。即使程序化绕过 selector，真实调用仍走权限、服务端 canonical request、Approval Binding、Case Version/CAS、Authorization、Idempotency 和 ERP gateway。Tool hidden 不是安全边界。

Runtime 对不存在的名称记录 UNKNOWN_CAPABILITY，对 registry 中存在但被 selector 隐藏的名称记录 SELECTOR_HIDDEN_TOOL，并记录 selectionStage。singleUse 的重复调用保留原独立拒绝语义。任务因必要工具被隐藏而失败时应经 trace 审计归为 TOOL_SELECTION_MISS；不能仅凭一次 hidden attempt 把整个失败归因给 selector，也不能归为模型幻觉后丢弃。

## Metrics 与归因

每轮记录 candidate_tools、selected_tool_names、hidden_tools（含原因）、selection_stage、candidate/visible/hidden tool counts、tool_schema_estimated_tokens 和 candidate_schema_estimated_tokens。估算用现有 ConservativeTokenEstimator 对名称、描述、schema 求和，不等于 provider 计费。

离线报告给出平均可见数、schema 均值/P95、同一当前轮筛选前后 schema 估算，以及 REQ/SRC/RFQ 示例。same-turn schema 差值最直接反映 exposure；不同模型轨迹的总 Input / Total Tokens、Tool Calls、Peak Context 和延迟仅作 Dev×1 方向性观察。重复成功读取相同请求仅作诊断 proxy，不冒称“无用调用”语义判定。

Phase 4 Tool Result Projection、Conversation Compaction、Prompt 和工具语义保持不变。仅 context budget 的 schema 预留以及 ContextAssembler 输入改为本轮实际 selected definitions。

## 验证与成本

确定性测试覆盖主要阶段、跨阶段 Core、unknown/corrupt、当前版本 Finalize、HITL/pending/resume/singleUse、直接绕过依然受真实权限检查，以及 Runtime 实际 model schema 和 hidden/unknown 错误分类。

`evals/dynamic-tool-loading/run.py` 复用冻结 world、driver、grader 和 Smoke9/Dev28 selection；Dev 要求 same-source Smoke9/9。五项 invariant 全部 fail fast，infra attempt 保留后才允许同源 resume。最多 9 Smoke + 28 Dev 有效 trials；不运行 Holdout、40×3 或 Blind，不以有效失败重跑刷绿。最终实际结果与限制见 `phase5-report.md`。
