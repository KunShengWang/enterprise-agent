# 项目讲解：采购 Agent

项目把自然语言采购需求转为可审计的 Case，通过 Provider 获取来源可追溯的候选，模型负责开放式权衡，Java 负责硬约束、版本、权限及最终推荐验证。需要时调用 Commercial/Delivery Specialist，再经不可变请求和人工审批创建模拟 RFQ。

可以重点讲：
- 为什么采用 Agent：需求澄清与多维权衡存在不确定性；金额计算、Eligibility 和 CAS 应由确定性代码完成。
- 公共 Runtime：原生 Tool Calling、上下文重注入/压缩、检查点、预算和恢复。
- HITL：批准绑定 exact request；结果未知应按幂等键查询，不盲目重试。
- Workbench：输入持久化、路由校验、Dispatch、事件投影与历史重放。
- MCP/Memory/RAG：只读 Provider、软偏好与动态业务事实分离、知识不得覆盖权限。
- 验证：采购离线 E2E、Multi-Agent、RFQ HITL 和公共安全测试；真实数据库和 Live Eval 单列。

明确不足：RFQ 是进程内模拟网关；没有真实 PO/付款，也没有采购 Child Run Workbench 树或聚合预算。历史数据库兼容尚待隔离环境验收。不要复述旧 FlowOrder 演示为当前功能。
