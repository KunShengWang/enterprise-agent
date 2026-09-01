# 企业采购寻源与供应商决策 Agent（第一阶段）

## 业务问题与 Agent 边界

复杂或非标准采购常以自然语言提出，采购人员需要跨商品目录、供应商报价和供应商资料反复搜索、整理，并在价格、交期、质量和风险之间做解释性比较。本阶段由 Agent 处理需求理解、必要澄清和调查路径选择，减少跨系统人工整理成本。

固定办公耗材补货、明确型号查最低价、预算计算、固定审批阈值、供应商 BLOCKED 判断、金额计算和固定评分公式不需要 Agent，继续由 Java、SQL 或 Rule 完成。Agent 只处理约束较多、候选不明确、证据来自多个来源且需要开放式权衡的采购请求。

## 第一阶段 READ-ONLY 链路

```text
自然语言采购需求
  -> Agent 多轮澄清
  -> procurement_case_patch（唯一内部状态写入路径）
  -> Java 校验并 CAS 合并 ProcurementCaseState
  -> procurement_supplier_search（Provider 查询 + Java Eligibility）
  -> Agent 根据 ToolResult 决定是否补充 supplier evidence
  -> procurement_recommendation_finalize（Java 重读并验证版本、资格、证据）
  -> Evidence-backed Supplier Recommendation
```

Search 只返回候选、报价、Provider canonical 证据和硬约束过滤结果，不在 Java 中评分或隐式代选；多个 Eligible Supplier 的价格、交期、质保和风险由 Agent 做透明 trade-off。当前止于 Recommendation，不创建 RFQ/PO，不执行审批、收货、发票或付款。Case Patch 虽然会写入内部 `ProcurementCaseState`，但不是采购业务副作用。

## 职责边界与状态

- Agent：理解目标，区分 hard constraints 与 preferences，提出 Case Patch，根据 ToolResult 动态决定调查路径，解释 trade-off，并提交带 evidenceRefs 的推荐草案。
- Java Runtime：Profile 白名单、Tool Schema 校验、CaseState 持久化、CAS 版本控制、总价计算、预算/排除供应商/数值硬约束、证据和最终推荐校验。唯一生产 Case 写入路径是 Agent Patch → Java validate → CAS；没有 Parser/upsert 或模型直接传入 search-state 的兼容路径。
- Human：本阶段只消费推荐结果；后续阶段再确认是否进入 RFQ。

`ProcurementCaseState` 是同一 tenant/user/conversation 当前采购任务的结构化权威状态，例如数量、预算、交期、显存下限和排除供应商。模型只能提交 `ProcurementCasePatch`，不能提交身份、版本、`missingFields` 或 `currentPhase`；Patch 对标量字段支持更新和 `fieldsToClear` 显式清除，对 hard constraint、preference 和排除供应商支持 upsert/remove。Java 合并后把状态持久化到与 Agent Runtime 共用的 storage datasource 的 `procurement_case_state` 表，并用 version CAS 拒绝并发静默覆盖。`appliedInputIds` 最多保留 128 条，用于避免有限窗口内的旧输入重放。

Long-Term Memory 只负责用户跨任务偏好；当前 Case 的采购事实不能依赖 Memory。报价、库存、交期和规格等供应商动态事实每次从 Provider/Tool 获取，不写入长期记忆。

## Provider / Adapter 与数据来源

Agent 只接触 `SupplierCandidate`、`SupplierOffer` 和 `SupplierEvidence` 等 canonical model。`AwsSyntheticProcurementProvider` 负责读取 AWS sample 原始 JSON 并转换模型，未来可替换为 SAP、ERPNext 或供应商 API Provider，不需要修改上层 Agent 和 Tool。

基础数据来源为 [aws-samples/sample-multi-agent-procure-to-pay](https://github.com/aws-samples/sample-multi-agent-procure-to-pay)，第一阶段只消费 `01_suppliers.json` 做 fallback supplier discovery；商品组、目录基准价以及 `04_material_requests.json`、`06_payment_terms.json` 和 `07_budgets.json` 当前不引入。数据是 synthetic and fabricated for demonstration，不是生产数据，来源说明见 `data/procurement/aws-synthetic/README.md`。AWS Base Dataset 不被当作供应商专属实时报价。

另外只保留一个明确标注的 project-specific scenario fixture：`complex_workstation_01.json` 自带 `sourceAsOf`，同时覆盖 Supplier A 排除、Supplier C 的 GPU 硬约束失败，以及 Supplier B/D 两个 Eligible Supplier 的价格/交期 trade-off。Provider 通过配置项 `enterprise-agent.procurement.scenario-file`（环境变量 `PROCUREMENT_SCENARIO_FILE`）显式选择 fixture，用户 preference 不会切换数据集。它不冒充 AWS 原始数据，也不构成通用报价数据集。

Java Eligibility/Evaluation 只计算 totalPrice、currency、budget、delivery、excluded supplier 和 supported hard constraint，返回 eligible/rejected candidates，不生成 recommendation、排序、评分或独立 Evidence。Evidence 只有 Provider canonical 路径生成；Evidence ID 绑定 supplier、type、source、sourceRecordId、sourceSnapshot、sourceAsOf、sourceDigest 和 fact，provenance 不完整或 ID 不一致会 fail-closed。即使只有一个 Eligible，也不会自动生成推荐或固定评分。Agent 必须根据用户 preference 和当前 ToolResult 做多候选权衡，再调用 `procurement_recommendation_finalize`。Finalize 会重读当前 Case 和 Provider snapshot，确认 selected supplier 仍 Eligible，所有 evidenceRefs 存在于当前 snapshot，且至少引用 selected supplier 的 OFFER；多个 Eligible 时还必须引用另一个 Eligible supplier 的 OFFER 并提交 tradeoffs，随后才生成 canonical `SourcingRecommendation`。

`ProcurementCaseState` 的权威来源是 tenant/user/conversation 维度的 Case Store，不是 Runtime metadata 副本。`procurement_case_patch` 是内部状态 mutation，Tool metadata 如实标记为 `readOnly=false/sideEffect=true`；search、evidence 和 finalize 仍为 `readOnly=true/sideEffect=false`，四者都不执行 RFQ、PO、审批、付款等采购业务动作。

## 明确非目标与后续路线

当前非目标：RFQ、PO、Receiving、Invoice、Payment、Procurement HITL、Procurement Multi-Agent、完整 P2P、SAP/ERPNext 部署、大规模 MCP/Memory/Context Compression 重构和真实电商 API。

1. Phase 2：Memory + Context Compression 优化
2. Phase 3：MCP Runtime 优化
3. Phase 4：Adaptive Multi-Agent
4. Phase 5：HITL + create_rfq
5. Phase 6：Eval / Ablation / Resume Metrics
