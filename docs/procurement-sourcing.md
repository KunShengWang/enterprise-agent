# 企业采购寻源与供应商决策 Agent（第一阶段）

## 业务问题与 Agent 边界

复杂或非标准采购常以自然语言提出，采购人员需要跨商品目录和供应商报价反复搜索、整理，并在价格、交期、质保和规格之间做解释性比较。本阶段由 Agent 处理需求理解、必要澄清和开放式选择，减少跨系统人工整理成本。

固定办公耗材补货、明确型号查最低价、预算计算、固定审批阈值、供应商 BLOCKED 判断、金额计算和固定评分公式不需要 Agent，继续由 Java、SQL 或 Rule 完成。Agent 只处理约束较多、候选不明确、证据来自多个来源且需要开放式权衡的采购请求。

## 第一阶段 READ-ONLY 链路

```text
自然语言采购需求
  -> Agent 多轮澄清
  -> procurement_case_patch（唯一内部状态写入路径）
  -> Java 校验并 CAS 合并 ProcurementCaseState
  -> procurement_supplier_search（Provider 查询 + Java Eligibility）
  -> Agent 基于 Search ToolResult 做多候选 trade-off
  -> procurement_recommendation_finalize（Java 重读并验证版本、资格、证据）
  -> Evidence-backed Supplier Recommendation
```

Search 返回候选、报价、Provider canonical 证据和硬约束过滤结果，不在 Java 中评分或隐式代选；多个 Eligible Supplier 的价格、交期、质保和规格由 Agent 做透明 trade-off。当前止于 Recommendation，不创建 RFQ/PO，不执行审批、收货、发票或付款。Case Patch 虽然会写入内部 `ProcurementCaseState`，但不是采购业务副作用。

## 职责边界与状态

- Agent：理解目标，区分 hard constraints 与 preferences，提出 Case Patch，根据 Search ToolResult 在多个 Eligible Supplier 中作选择，提交 selectedSupplierId、evidenceRefs、受限 `tradeoffDimensions` 和 confidence；Finalize 成功后再给出中文解释。
- Java Runtime：Profile 白名单、Tool Schema 校验、CaseState 持久化、CAS 版本控制、总价计算、预算/排除供应商/数值硬约束、证据和最终推荐校验。唯一生产 Case 写入路径是 Agent Patch → Java validate → CAS；没有 Parser/upsert 或模型直接传入 search-state 的兼容路径。
- Human：本阶段只消费推荐结果；后续阶段再确认是否进入 RFQ。

`ProcurementCaseState` 是同一 tenant/user/conversation 当前采购任务的结构化权威状态，例如数量、预算、交期、显存下限和排除供应商。模型只能提交 `ProcurementCasePatch`，不能提交身份、版本、`missingFields` 或 `currentPhase`；Patch 对标量字段支持更新和 `fieldsToClear` 显式清除，对 hard constraint、preference 和排除供应商支持 upsert/remove。Java 合并后把状态持久化到与 Agent Runtime 共用的 storage datasource 的 `procurement_case_state` 表，并用 version CAS 拒绝并发静默覆盖。`appliedInputIds` 最多保留 128 条，用于避免有限窗口内的旧输入重放。

Long-Term Memory 只负责用户跨任务的软偏好或稳定交互指令，自动 Durable 类型仅有 `PREFERENCE` 和 `STABLE_INSTRUCTION`。`userId` 是 durable recall scope，`conversationId` 只记录 provenance；当前 Case 的采购事实不能依赖 Memory。报价、库存、交期、规格、预算、数量、供应商选择和排除等动态事实每次从 Provider/Tool 或当前 Case 获取，不写入长期记忆。

### Phase 2A：权威上下文重注入与记忆门控

采购 Profile 的每个模型轮次都会通过 `DefaultAgentContextManager`，使用可信的 `tenantId + userId + conversationId` 重新读取 `ProcurementCaseStore`。Runtime 只依赖极薄的 `AgentCanonicalContextProvider` SPI，`ProcurementCaseContextRenderer` 是当前采购实现；它将 `caseId/caseVersion/status/ProcurementCaseState` 作为一次性的 canonical context 投影给模型。该投影不缓存 Case、不写入 Timeline、不参与 `CONTEXT_SUMMARY` 摘要，也不进入长期记忆。该投影会优先占用上下文预算，不能被 recent history 裁剪；预算不足时 Runtime fail-closed。上下文压缩、Run resume 或下一模型轮会重新读取 CaseStore，因此不能继续使用过期 Case。

canonical business context 使用独立的 `AgentMessageType.CANONICAL_CONTEXT` 和 `<canonical_business_context authoritative_business_data="true" trusted_instructions="false">` 协议包装；持久化会话摘要仍使用 `AgentMessageType.CONTEXT_SUMMARY` 和 `<context_summary untrusted_data="true">`。metadata 标记 `source=authoritative-procurement-case-state`、`caseId`、`caseVersion`、`fresh=true` 和 `trustedInstructions=false`。状态中的用户字符串只能作为业务数据，不能提升为 SYSTEM 指令。

Phase 2A 的 Context Manager 门控仍由 Profile 统一控制长期记忆写入、recall、User Profile 加载和 synthetic `<memory_context>` 注入；关闭时 PostgreSQL Timeline、持久化 `CONTEXT_SUMMARY` 和工具调用/结果的 `MessageUnit` 原子配对仍正常工作。Runtime 事件区分本轮普通 projection、`context_budget` 压缩和 `provider_context_overflow` 有界重试，并记录 token budget、压缩前后消息/Token/遗漏数量和覆盖序列。

### Phase 2B：Typed Durable Memory

采购 Profile 现已开启 `longTermMemoryEnabled=true`。只有用户明确表达“以后/今后/默认/通常/长期/请记住”等跨任务意图，且没有“这次/本次/当前/今天/本项目”等 ephemeral cue 时，才会调用 Memory LLM；ephemeral veto 优先于 durable intent。LLM 只能提出 `PREFERENCE` 或 `STABLE_INSTRUCTION` candidate，并选择当前用户消息中的 verified exact source span；实际持久化的 candidate 与自动 UserProfile value 自身也必须包含 durable intent，Java 对类型、非空内容、有限 `[0,1]` confidence 和敏感信息再次 fail-closed 校验，Jdbc persistence boundary 还会重新核验原文 grounding；自动 UserProfile 仅允许 `language`、`response_style`，无稳定 `userId`（`anonymous`、`anonymous-user`、null 或 blank）时自动写入或加载 fail-closed，显式 profile upsert 与 user memory clear 仍保持公共管理契约。

采购预算、数量、交期、当前 Case 状态、排除或选中的供应商、报价、库存和规格等一次性事实不会进入 Durable Memory，即使模型错误建议保存也会被 Java 边界拒绝。Memory 只作为不可信的 `<memory_context>` 软上下文，不能覆盖当前用户意图、canonical `ProcurementCaseState`、Java Eligibility 或 ToolResult；供应商事实仍只能来自当前 Case/Tool。Recall 按 `userId` 隔离，并只读取新的 typed 持久化值 `PREFERENCE`、`STABLE_INSTRUCTION`；历史 lowercase `preference`、`instruction` 以及 `business_fact`、`decision`、`open_task`、`identity` 等 unsafe category 不再召回，不做破坏性迁移。

## Provider / Adapter 与数据来源

Agent 只接触 `SupplierCandidate`、`SupplierOffer` 和 `SupplierEvidence` 等 canonical model。`AwsSyntheticProcurementProvider` 负责读取 AWS sample 原始 JSON 并转换模型，未来可替换为 SAP、ERPNext 或供应商 API Provider，不需要修改上层 Agent 和 Tool。

Provider backend 由 `enterprise-agent.procurement.provider` 选择，默认是 `synthetic`；设置为 `mcp` 时，Spring 只装配 `McpProcurementDataProvider`，通过冻结的 `McpToolGateway` 读取只读事实。调用边界保持为：

```text
Agent
  -> procurement_supplier_search / procurement_recommendation_finalize
  -> ProcurementDataProvider
  -> McpToolGateway
  -> mcp.procurement.search_suppliers / mcp.procurement.get_offers
```

`mcp.procurement.search_suppliers` 和 `mcp.procurement.get_offers` 是 Provider backend API，不是模型可见 Tool；采购 Profile 仍只暴露 `procurement_case_patch`、`procurement_supplier_search` 和 `procurement_recommendation_finalize` 三项。Provider 每次从当前 immutable MCP tool snapshot 精确解析工具，并使用 bound `ToolDefinition` 调用，不自动 refresh、retry 或回退 Synthetic。MCP 只提交商品类别、描述、数量、币种和候选供应商 ID 等必要事实查询字段，不提交预算、交期、排除项、偏好或 hard constraints。

远端响应只被当作不可信业务资料：Java 严格校验 snapshot/as-of、ID、价格、交期、规格和候选归属，重新计算 `totalPrice`，由 Java `ProcurementDecisionEngine` 计算 Eligibility，并从 canonical offer 生成 Evidence、provenance 和 `sourceDigest`。缺字段、错误类型、重复工具/供应商/报价、MCP 调用失败或当前快照不可用都会 fail closed；不会静默返回空数据或切换 Synthetic。`source` 只记录安全的 `mcp:<mcpServerId>`，不记录 command、args、工作目录或凭证。

MCP 集成测试使用自包含的 JVM `FakeProcurementMcpServerApplication`，由独立 child process 通过 stdio 实现 `initialize`、`tools/list` 和 `tools/call`，并读取现有 `data/procurement/scenarios/complex_workstation_01.json`。它是 synthetic integration fixture，不是真实 ERPNext、SAP 或生产供应商系统；因此简历表述应是“在 Provider/Adapter 边界接入只读 MCP 事实源”，不能夸大为已接入真实 ERP。

基础数据来源为 [aws-samples/sample-multi-agent-procure-to-pay](https://github.com/aws-samples/sample-multi-agent-procure-to-pay)，第一阶段只消费 `01_suppliers.json` 做 fallback supplier discovery；商品组、目录基准价以及 `04_material_requests.json`、`06_payment_terms.json` 和 `07_budgets.json` 当前不引入。数据是 synthetic and fabricated for demonstration，不是生产数据，来源说明见 `data/procurement/aws-synthetic/README.md`。AWS Base Dataset 不被当作供应商专属实时报价。

另外只保留一个明确标注的 project-specific scenario fixture：`complex_workstation_01.json` 自带 `sourceAsOf`，同时覆盖 Supplier A 排除、Supplier C 的 GPU 硬约束失败，以及 Supplier B/D 两个 Eligible Supplier 的价格/交期 trade-off。Provider 通过配置项 `enterprise-agent.procurement.scenario-file`（环境变量 `PROCUREMENT_SCENARIO_FILE`）显式选择 fixture，用户 preference 不会切换数据集。它不冒充 AWS 原始数据，也不构成通用报价数据集。

Java Eligibility/Evaluation 只计算 totalPrice、currency、budget、delivery、excluded supplier 和 supported hard constraint，返回 eligible/rejected candidates，不生成 recommendation、排序、评分或独立 Evidence。Evidence 只有 Provider canonical 路径生成；Evidence ID 绑定 supplier、type、source、sourceRecordId、sourceSnapshot、sourceAsOf、sourceDigest 和 fact，provenance 不完整或 ID 不一致会 fail-closed。即使只有一个 Eligible，也不会自动生成推荐或固定评分。Agent 必须根据用户 preference 和当前 ToolResult 做多候选权衡，再调用 `procurement_recommendation_finalize`。Finalize 会重读当前 Case 和 Provider snapshot，确认 selected supplier 仍 Eligible，所有 evidenceRefs 存在于当前 snapshot，且至少引用 selected supplier 的 OFFER；多个 Eligible 时还必须引用另一个 Eligible supplier 的 OFFER 并提交受限的 `tradeoffDimensions`，随后由 Java 构造 canonical `SourcingRecommendation`。

Phase 1 的 Agent Tool 只有 `procurement_case_patch`、`procurement_supplier_search` 和 `procurement_recommendation_finalize`。Search 已返回本阶段需要的 Provider Offer 和 Evidence；没有独立的供应商深入调查来源。质量历史、履约率、库存、供应商风险、认证和合同状态若未出现在 ToolResult 中，Agent 必须明确说明“当前数据未提供”，不得推断或补写。

`SourcingRecommendation` 是权威业务记录，但字段分为两层：

- **Verified Facts**：`recommendedSupplier`、`selectedOffer`、`eligibleAlternatives`、`alternativeOffers`、`matchedConstraints`、`rejectedCandidates` 和 `evidenceRefs`。这些事实由 Java 根据当前 Provider snapshot 构造并验证；价格、总价、交期、质保、规格和 Eligibility 不由 Agent 提交。`eligibleAlternatives` 与 `alternativeOffers` 必须按位置一一对应并表达同一组 Supplier，不能包含 null、重复 Supplier ID 或 recommended supplier。
- **Agent Decision Metadata**：`tradeoffDimensions` 与 `confidence`。它们由 Agent 提交，用来说明选择时关注的权衡维度和主观决策置信度，不是 Provider 事实。

`confidence` 只表示 Agent 对本次 Supplier Selection 的主观决策置信度，不表示 Provider 数据真实性概率、Supplier 实际履约概率、Supplier 风险概率、推荐正确率、Eligibility 置信度或统计概率。Java 只校验有限值满足 `0 <= confidence <= 1`，`NaN`、`±Infinity` 和越界值均直接拒绝，不增加计算或静默修正逻辑。Agent 的最终中文 explanation 属于展示层，只能在 Finalize ToolResult 之后基于其中 verified facts 生成，不写入 canonical Recommendation。

`ProcurementCaseState` 的权威来源是 tenant/user/conversation 维度的 Case Store，不是 Runtime metadata 副本。`procurement_case_patch` 是内部状态 mutation，Tool metadata 如实标记为 `readOnly=false/sideEffect=true`；search 和 finalize 为 `readOnly=true/sideEffect=false`，三者都不执行 RFQ、PO、审批、付款等采购业务动作。

## 明确非目标与后续路线

当前非目标：RFQ、PO、Receiving、Invoice、Payment、Procurement HITL、Procurement Multi-Agent、完整 P2P、SAP/ERPNext 部署、大规模 MCP/Memory/Context Compression 重构和真实电商 API。Phase 2A 只补充当前 Case 的权威投影和 Runtime 级上下文门控，不建设通用 Context Provider/Contributor/Plugin 框架；`AgentCanonicalContextProvider` 仅是当前所需的极薄 SPI。

1. Phase 2A（已落地）：权威 Case 上下文重注入、长期记忆门控和压缩可观测性
2. Phase 2B（已落地）：Typed Durable Memory 的提取边界、user scope 与不可信上下文接入
3. Phase 3：MCP Runtime 冻结后的 Procurement 只读 Provider 接入
4. Phase 4：Adaptive Multi-Agent
5. Phase 5：HITL + create_rfq
6. Phase 6：Eval / Ablation / Resume Metrics
