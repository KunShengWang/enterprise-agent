# Procurement V2 总升级方案（仅设计冻结，未实施）

日期：2026-10-02。当前阶段只冻结 [Procurement V0](procurement-v0-baseline.md)。本文记录已确定方向，不授权开始 Phase 1，也不宣称以下能力已经存在。

## 1. 未来业务范围

```text
Natural-language Procurement Request
  → Requisition Analysis
  → Supplier Sourcing
  → Supplier Recommendation
  → RFQ Preparation
  → HITL Approval
  → ERPNext RFQ Creation
```

终点是审批后创建 ERPNext RFQ。明确不做 Purchase Order、Goods Receipt、Invoice、Three-way Match、Payment、Web Supplier Discovery、真实 SAP 集成和复杂 Tool Search。

## 2. 未来 Agent 架构

```text
Procurement Supervisor
    ├── Requisition Agent
    ├── Sourcing Agent
    └── RFQ Agent
```

采用 **Supervisor + Agent-as-Tool**，暂不采用 Handoff。Supervisor 仅看到高级 Subagent Tools；细粒度 MCP/ERP Tools 下沉到相应领域 Subagent。当前已有 Commercial/Delivery advisory Specialist 与以上三领域 Agent 的业务职责不同；本轮不迁移或替换它们。

## 3. 未来系统架构与数据归属

```text
Agents
  ↓
Procurement MCP
  ↓
Canonical Procurement ERP Adapter
  ↓
ERPNext
```

- ERPNext 成为业务 System of Record。
- Agent Runtime DB 只保存 Agent / Governance 状态；不再重复维护 Supplier / Purchase History 业务主数据。
- Canonical Procurement ERP Adapter 是领域与 ERPNext 的边界。具体字段映射、失败契约和迁移方式待后续独立设计，不在本轮创建空抽象。
- Java 确定性规则继续负责业务硬约束；Agent 负责需求理解和选择理由，不绕过验证。
- RFQ 创建继续走 HITL；应保留精确动作绑定、版本检查、幂等和不确定结果对账等现有安全要求。
- `complex_workstation_01.json` 未来降级为 Legacy Regression Fixture，不再作为 Runtime 正式业务数据源；本阶段仍保留原 Runtime 行为与原路径。

## 4. 后续比较原则

V0 负责保护现有 Legacy 行为。ERPNext + Procurement V2 + Eval V2 建立后重新运行正式 **B0**，再比较 Harness 改造效果。V0 scripted usage、模拟 RFQ 和四 Case Live observation 不得直接充当真实 ERP/Harness 的正式提升基准。

需要在后续独立任务中明确：ERP 业务事实映射、Agent/Governance 与 ERP 记录关联、持久化幂等键及可查询 receipt、评测环境与数据重置边界、统一可比口径。当前代码的 process-local receipt 不能直接承担真实 ERP 的跨进程去重证明。

## 5. 本轮禁止实施与停止条件

不部署 ERPNext；不实现 ERPNext Adapter、新 Procurement MCP Tool、新 Supplier Seed Model、Requisition/Sourcing/RFQ Agent、Procurement Supervisor、Agent-as-Tool、AgentRegistry 或 SubAgentExecutor。

不重构 AgentRunner、RunState、ContextAssembler、Context Compaction、Memory、Dynamic Tool Loading、Tool Runtime、ToolPolicy；不建设 Workbench V2、Web Search 或新的 Multi-Agent Runtime。现有同类能力保持不变。

完成 V0 文档、回归入口、实测报告和差异检查后停止。人工 Review 后再单独决定 Phase 1 的范围和验收，不自动续做本方案。
