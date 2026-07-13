# 关键设计决策

## ADR-01：单一 Agent Runtime

决定：同步、SSE、主 Agent 和 Sub-Agent 复用同一个 Runtime；适配器不实现业务执行流程。

原因：避免审批、幂等、预算和 Guardrail 在不同入口产生不一致语义。

代价：Runtime 成为高内聚核心，需要通过 Store、Gateway、Capability 和 ToolRuntime 边界控制体积。

## ADR-02：模型驱动循环，不使用固定 Workflow 计划

决定：不预先生成 `LOAD_MEMORY -> ROUTE -> RAG -> TOOL -> LLM` 节点列表。

原因：真实 Agent 的下一步取决于模型输出和 ToolResult，固定流程无法表达失败后重新规划。

保留：`AgentRunPhase` 只表示粗粒度持久状态，不参与预定业务分支。

## ADR-03：PostgreSQL 时间线是事实源

决定：Session、Message、Event 和 Run 都持久化；Context 是投影。

原因：支持恢复、审计、多实例序号一致性和压缩不丢历史。

代价：每轮包含数据库读写；当前实现优先正确性和可解释性，尚未做批量写入或事件队列优化。

## ADR-04：工具结果必须成对

决定：ToolCall 和 ToolResult 使用相同 toolCallId，Timeline Store 和唯一索引共同维护约束。

原因：孤立工具消息会破坏模型对已执行副作用的理解。

## ADR-05：权限在 Runtime 强制执行

决定：System Prompt 只解释规则，真正的 Profile 白名单、allow/ask/deny、Schema 校验和审批由代码执行。

原因：模型输出是不可信请求，不能让模型自己决定是否遵守权限。

## ADR-06：短期上下文与长期记忆分离

决定：短期消息和 Context Summary 属于 Timeline；Memory Service 只保存长期记忆和画像。

原因：防止两套消息历史双写；明确长期记忆是有损、可召回、不可信的数据。

## ADR-07：语义主路径 + 确定性降级

决定：RAG 重排、Memory 提取、Prompt Injection 判断使用模型/Embedding 主路径；词法或格式规则只在适合的位置降级。

原因：纯关键词不能代表语义，但确定性格式检查在 DLP、Schema 和安全失败中仍有价值。

## ADR-08：生产实现不提供 InMemory 模式

决定：所有业务持久状态只装配 PostgreSQL/JDBC/pgvector 实现。

原因：避免学习和演示在单进程内看似正常，却无法证明恢复、多实例或重启语义。

例外：Runtime 内部正在执行的 Future、取消句柄和锁条带属于进程资源，不是业务事实；相应取消请求和租约仍持久化。
