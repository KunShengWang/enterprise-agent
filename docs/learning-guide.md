# 学习路径：采购业务与公共 Agent 平台

1. 从 [采购指南](procurement-sourcing.md) 理解 Case Patch、Provider 证据、硬约束校验与模型权衡的分工。
2. 阅读 config 中 General/Procurement Profile 及 AgentScenarioProfileResolver，理解工具白名单和退役拒绝。
3. 跟踪 DefaultAgentRuntime、ToolRuntime、Context Manager：模型循环、检查点、上下文压缩、预算和恢复。
4. 跟踪采购 Commercial/Delivery Specialist 与通用 SubAgentRunner；子 Agent 仅给建议，最终推荐仍由主 Agent 提交并经 Java 校验。
5. 阅读 RFQ Preparer/Handler、通用审批与模拟网关，理解不可变请求、幂等键及结果未知时禁止盲重试。
6. 跟踪 Workbench Router/Dispatch/PublicPresentation/Run 投影，理解输入幂等、任务历史、cursor 与 fencing。
7. 学习 Memory、RAG、MCP、Trace 与 Eval。知识与模型输出不能覆盖可信身份或 Case 事实。

运行离线测试后再阅读报告；Live Eval 和数据库 IT 是单独门禁，不因为本地编译通过就视为完成。旧领域设计只作历史对照，不应据其补建已删除模块。入口见 [文档索引](documentation-index.md)。
