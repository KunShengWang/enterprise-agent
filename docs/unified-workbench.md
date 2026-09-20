# 当前 Unified Workbench

采购输入通过 POST /api/agent/conversations/{conversationId}/inputs 持久化；202 Accepted 表示接收，不是业务完成。之后读取 WorkItem、PublicPresentation、Run 和 SSE 事件。

当前执行目标为 PROCUREMENT_SOURCING 和受限 GENERAL_AGENT。模型仅提出路由，Java 检查目标、权限、字段来源与受保护元数据；不能恢复已退役目标。采购输入不得由旧 requestId/orderNo/deductNo 自动映射产生。

通用 RoutePreview 的版本、摘要、租户、权限与过期约束保留；RFQ 审批是另一条业务安全边界，不能把路由确认等同于 RFQ 授权。采购推荐不会自动创建 RFQ。

Dispatch 保留稳定请求身份及重试/对账。旧 Dispatch、旧 Preview、WorkCommand 和旧工具被公共退休边界拒绝；不改写历史任务状态。

当前投影仅处理 AGENT_RUN。领取在 LIMIT 与租约操作前排除旧 source；已物化 WorkEvent 保留读取。公共 cursor、去重、claim/lease/fencing 与权威版本终态收敛必须持续验证。

界面可查看历史、工具调用、预算与 Trace。旧 Incident/Recovery 链接为 RETIRED 只读说明，不补建完整旧执行树，不从旧 Store 补投事件。当前没有采购 Child Run 执行树或采购聚合预算。

[API](api-guide.md) · [前端](frontend-learning-console.md) · [隔离数据库验收边界](retirement-and-history.md)
