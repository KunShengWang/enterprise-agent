# 企业采购寻源与供应商决策 Agent（第一阶段）

## 业务问题

复杂或非标准采购通常以自然语言提出，采购人员需要跨商品目录、供应商报价和供应商资料反复搜索、整理并比较价格、交期、规格和风险。本阶段用 Agent 处理开放式需求理解、必要澄清和调查路径选择，减少人工整理信息的成本。

固定办公耗材补货、明确型号查最低价、预算计算、固定审批阈值、供应商 BLOCKED 判断和固定评分公式不需要 Agent，继续由 Java、SQL 或 Rule 完成。

## 第一阶段链路

```text
自然语言需求
  -> 需求理解与多轮澄清
  -> ProcurementCaseState
  -> 供应商/报价只读查询
  -> Java 硬约束、排除和预算过滤
  -> Evidence-backed Supplier Recommendation
```

当前止于 Recommendation，不创建 RFQ/PO，不执行审批、收货、发票或付款。

## 职责边界

- Agent：理解目标，区分 hard constraints 与 preferences，根据 ToolResult 决定是否继续调查，并解释 trade-off。
- Java Runtime：Profile 白名单、Tool Schema 校验、CaseState 持久化、总价计算、预算/排除供应商/数值硬约束和运行状态控制。
- Human：在后续阶段确认推荐并决定是否进入 RFQ；本阶段不产生采购副作用。

`ProcurementCaseState` 是同一 conversation 当前采购任务的结构化权威状态，例如数量、预算、交期、显存下限和排除供应商。它通过与 Agent Runtime 共享的 storage datasource 持久化到 `procurement_case_state`，多轮消息按 conversation 增量更新。Long-Term Memory 仍只负责跨任务的用户偏好；报价、库存、交期等供应商动态事实每次从 Provider/Tool 获取，不写入长期记忆。`appliedInputIds` 只保留最多 128 条历史输入，用于防止有限窗口内的旧输入重放。

## Provider 与数据

Agent 只接触 `SupplierCandidate`、`SupplierOffer` 和 `SupplierEvidence` 等 canonical model。`AwsSyntheticProcurementProvider` 负责读取并转换 AWS sample 原始 JSON，未来可替换为 SAP、ERPNext 或供应商 API Provider。

基础数据来源为 [aws-samples/sample-multi-agent-procure-to-pay](https://github.com/aws-samples/sample-multi-agent-procure-to-pay)，本仓库仅使用 `01_suppliers.json`、`02_item_groups.json` 和 `03_items.json`。这些记录是 synthetic and fabricated for demonstration，不是生产数据。本项目额外加入 `data/procurement/scenarios/complex_workstation_01.json`，它是一个小型 project-specific fixture，用于明确验证 Supplier A 被排除、Supplier C 的 GPU 显存硬约束失败、Supplier B 被推荐。

## 后续路线

1. Phase 2：Memory + Context Compression 优化
2. Phase 3：MCP Runtime 优化
3. Phase 4：Adaptive Multi-Agent
4. Phase 5：HITL + create_rfq
5. Phase 6：Eval / Ablation / Resume Metrics
