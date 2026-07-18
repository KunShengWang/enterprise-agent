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

## ADR-09：恢复必须携带原执行身份和累计预算

决定：Run 持久化 ExecutionProfile、BudgetSnapshot 与 Phase；审批恢复采用原子 claim，崩溃恢复依赖唯一 leaseOwnerId 和过期租约。

原因：恢复不能获得新的权限或预算；工具副作用检查点先查询持久化执行记录，确定成功或失败时复用结果继续规划，只有无法证明结果时才进入人工核对。

## ADR-10：模型 ToolCall ID 不作为全局幂等键

决定：Runtime 为每次模型工具请求生成全局执行 ID，原始模型 ToolCall ID 仅写入消息和事件元数据用于追踪。工具执行存储发现执行 ID 已属于其他 Run 时拒绝复用并进入人工核对。

原因：不同 Run 的模型可能重复生成 `call-1` 等局部 ID；直接作为数据库全局主键会错误复用其他任务的副作用结果。

## ADR-11：审批状态只能原子迁移一次

决定：审批决定与过期处理使用数据库条件更新，将 `REQUESTED` 原子迁移到 `APPROVED/REJECTED/EXPIRED`。批准或拒绝还必须满足 `expiresAt>decisionTime`，过期迁移必须满足 `expiresAt<=checkedAt`。条件更新失败后读取数据库中的胜出状态，同方向请求幂等返回，冲突方向请求明确失败。

原因：先读取再无条件保存会产生并发覆盖，可能使已经按批准结果执行的高风险工具在记录中又显示为拒绝。

## ADR-10：SSE 是可丢传输，PostgreSQL Event 才是事实

决定：SSE 暴露持久序号、发送心跳并显式报告缓冲缺口；客户端通过 `afterSequence` 补拉。

原因：网络流无法承诺永久可靠，静默丢事件会让客户端形成错误状态。

## ADR-11：模型只接收 ToolResult 有界投影

决定：完整工具原文保存在 ToolExecutionStore；Timeline 只保存有界摘要、哈希和 rawReference，并对 Prompt 结构边界转义。

原因：外部工具结果既可能过大，也是不可信内容，不能直接拼入 Prompt。

## ADR-12：能力定义与 Spring AI ToolCallback 执行分离

决定：`AgentCapabilityRegistry` 保存项目自己的 `ToolDefinition`，统一聚合 RAG、Skill、本地业务工具和 MCP 工具；它不把可直接执行业务方法的 Spring AI `ToolCallback` 注册为 Runtime 执行入口。

原因：`ToolCallback` 同时提供模型可见定义与 `call()` 执行能力。若使用 `ChatClient + ToolCallingAdvisor` 自动执行，会在 `DefaultAgentRuntime` 之外形成第二套工具循环，绕过 ExecutionProfile 白名单、Schema 校验、allow/ask/deny、人工审批、执行 claim、幂等结果复用、UNKNOWN 对账和统一审计。Spring AI 官方同时支持 user-controlled tool execution，并提醒默认工具会共享给所有请求，使用不当可能暴露不该开放的能力。

约束：能力名称必须全局唯一；内建能力、业务 Contributor 和 MCP 发现结果发生同名冲突时启动或调用应明确失败，不能把歧义目录交给模型。

演进：接入 Provider 原生 Tool Calling 时，在 `AgentModelGateway` 增加定义适配器，将领域 `ToolDefinition` 转换为 Spring AI/Provider 的工具 Schema，并把模型返回的 ToolCall 交还 `AgentToolRuntime`。适配器只负责协议转换，不直接产生业务副作用。参考 [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)。
