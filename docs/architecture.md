# 当前架构：采购 Agent

当前执行目标仅为 PROCUREMENT_SOURCING 和受限 GENERAL_AGENT；场景 Profile 分别为 procurement-sourcing-rfq-v1、general-agent-v1。

## 执行链

用户输入 → Unified Workbench 持久化输入/WorkItem → Router 提出目标 → Java 验证目标、权限、受保护字段 → Dispatch → Agent Runtime → Profile 限定的 Capability / ToolRuntime → 结果持久化与公共投影。

Runtime 负责模型循环、消息上下文、原生 Tool Calling、预算、检查点、租约和审批暂停/恢复。同步、流式及采购 Specialist 使用通用 Runtime，不能由前端或模型另建业务执行通道。JSON 模式是兼容路径，不是默认架构。

## 采购领域

Case Patch 是内部 Case 写入入口；服务端校验并 CAS 合并。Provider 提供候选和 canonical 来源事实，Java 执行硬约束与 Eligibility，模型负责非确定性的需求理解和权衡。Recommendation Finalize 重读权威状态并验证证据。

Commercial/Delivery Specialist 使用通用 SubAgentRunner，接收隔离、裁剪的上下文，仅返回 advisory 分析，不写 Case、不调用供应商 Provider、不形成最终推荐。未实现采购 Child Run Workbench 执行树或聚合预算。

RFQ Preparer 从本 Run 的成功 Finalize 和当前 Case 重建 exact request；批准后执行保存的请求。SimulatedProcurementRfqGateway 仅为进程内模拟网关，不代表真实供应商连接、订单创建或付款。

## 公共能力

- MCP：通用网关、发现与调用边界；采购 Provider 为只读数据接入，不能据此扩大写工具权限。
- Memory：跨任务软偏好/稳定指令；报价、预算、数量和选择属于 Case/Provider，不作为长期权威事实。
- RAG：本地语料加载、切分、Embedding、pgvector、检索与引用；知识内容是不可信数据，不能授予工具权限。
- Trace/Eval/AgentOps：记录执行证据；冻结 Benchmark 与 Live Eval 的开关和结果分开管理。
- WorkItem/Router/General/Procurement 预算：沿用公共账本和限额保护。

## 投影与历史

AGENT_RUN 是当前后台投影源；sequence、cursor、claim/lease/fencing、去重及权威版本终态收敛保留。Primary Run MODEL_DELTA 与 WorkEvent/PublicPresentation 各自承担流式回答和可读历史职责，不引入第二套状态机。

旧 Incident/Recovery source 不再领取或补投；已物化公共历史仍可读取。旧链接显示 RETIRED，旧历史状态不改写。数据库表仍保留，真实 PostgreSQL 历史兼容尚待隔离环境验收。

详见 [Workbench](unified-workbench.md)、[采购实现](procurement-sourcing.md)、[历史边界](retirement-and-history.md)。
