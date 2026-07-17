# Enterprise Agent

一个以“可解释、可恢复、可约束的 Agent Runtime”为中心的 Java 17 / Spring Boot 4 学习项目。

项目不再使用固定的 `Route -> RAG/Tool -> LLM` 业务流水线。模型在统一循环中决定返回最终文本或请求能力调用；Runtime 负责时间线、预算、权限、审批、工具执行、上下文压缩、终止原因和事件持久化。

## 核心执行语义

```text
用户请求
  -> 输入 Guardrail
  -> PostgreSQL Session / Run / Message / Event
  -> Context 投影（长期记忆 + 摘要 + 完整工具对 + 近期消息）
  -> 模型返回 assistantText 或 toolCalls
  -> Runtime 校验能力白名单、JSON Schema 和 Tool Policy
  -> allow / ask / deny
  -> 工具执行或等待人工审批
  -> ToolResult 追加回同一消息时间线
  -> 再次调用模型
  -> 直到最终回答或显式停止原因
```

同步接口和 SSE 接口都调用同一个 `AgentRuntime`。SSE 只是转发已落库的 Runtime 事件，不包含另一套简化 Agent 逻辑。

## 当前工程能力

- 统一模型驱动 Agent Loop：最大轮次、模型/工具次数、Token、可配置价格成本和运行时长预算；Profile 与累计 Budget 随 Run 持久化。
- 严格消息时间线：`USER -> ASSISTANT_TOOL_CALL -> TOOL_RESULT -> ASSISTANT_TEXT`，工具调用与结果不能拆对。
- Context Budget：滚动摘要、完整工具单元裁剪、模型上下文溢出后的有限压缩重试。
- Runtime Tool Policy：执行 Profile 白名单、allow/ask/deny、参数 Schema 校验、审批暂停/恢复、副作用幂等。
- PostgreSQL 持久化：Run、Session、Message、Event、审批、工具执行、限流、Trace、Eval、Skill、RAG 缓存等均无生产 InMemory 实现。
- RAG：真实 Embedding、pgvector、向量 + 关键词混合召回、模型语义重排、确定性降级、引用元数据、持久化 TTL 缓存。
- Memory：Runtime 时间线负责短期上下文；结构化模型提取后的长期记忆使用 pgvector 召回；用户画像独立持久化。
- Guardrail：输入规范化、确定性注入信号 + 模型语义确认、分层 DLP、输出脱敏、工具策略审计。
- 可靠性：唯一执行租约、原子恢复抢占、过期 RUNNING 检查点恢复、跨实例取消、有界模型线程池、超时重试、熔断和明确终止原因。
- ToolResult 隔离：完整原文保存在工具执行记录，模型时间线只接收带哈希和 rawReference 的有界投影，并转义结构边界。
- SSE 可靠性：持久化事件序号、心跳、背压缺口通知；客户端可以识别不完整事件流。
- Sub-Agent：独立 Session、System Prompt、能力白名单、预算和父子 Run；只向协调者返回摘要与 childRunId。
- AgentOps：Runtime 事件投影为 Trace / Eval / Replay，保留真实 Usage 或明确标记估算来源。
- OrderCare M3：与 FlowOrder 联动完成异常订单诊断、不可变 Proposal、版本化审批、领域幂等 execute、确定性收敛、UNKNOWN 对账、Action 执行租约与崩溃恢复；覆盖响应丢失、进程重启和重复 resume，并通过 20/20 真实模型 Eval。

## 当前业务主线：OrderCare

项目不再以“通用 Agent 能力数量”作为完成标准，当前主线是：

> 面向 FlowOrder 异常订单的智能诊断与受控恢复系统。

M3 已通过并达到 **Interview Strong**：Agent 负责理解入口、选择诊断/知识/预演能力和解释结果；FlowOrder 负责交易事实、候选动作、Proposal、Action 租约与对账状态；人工审批具体预演版本；确定性 Java 代码执行有界收敛与未知结果恢复。`SUBMITTED` 与 `RESOLVED` 始终分开。

详见 [OrderCare M3 故障正确性证据](docs/reports/ordercare/m3-fault-correctness.md)。M4 的可信身份、服务认证、版本化迁移、部署与告警尚未完成，因此仍不宣称生产级。

## 技术栈

- Java 17
- Spring Boot 4.1 / WebFlux
- Spring AI 2.0 / DeepSeek ChatModel
- PostgreSQL + pgvector
- MCP stdio（可选）
- Maven

## 快速启动

前置条件：Java 17、PostgreSQL、已安装 pgvector 扩展。应用会按需创建业务表，但数据库账号必须有建表和 `CREATE EXTENSION vector` 权限。

PowerShell：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek Key"
$env:EMBEDDING_API_KEY="你的 Embedding Key"
$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="你的数据库密码"

mvn clean spring-boot:run
```

`MEMORY_POSTGRES_*` 和 `AGENT_STORAGE_POSTGRES_*` 未设置时会复用 `RAG_POSTGRES_*`。源码和 YAML 不提供默认密码。

只验证 Spring 上下文时可使用 Mock 模型，但 Runtime 仍需要 PostgreSQL：

```powershell
$env:ENTERPRISE_AGENT_MOCK_MODE="true"
$env:DEEPSEEK_API_KEY="test-key"
$env:EMBEDDING_API_KEY="test-key"
mvn clean test
```

迁移自旧版代码后必须执行 `mvn clean`，否则 `target/classes` 可能保留已经删除的旧 Bean 类。

## 最小调用

```powershell
$body = @{
  conversationId = "demo-session-1"
  userId = "demo-user"
  question = "请诊断 requestId=ORDERCARE-M05-REQUEST；符合条件时创建预演并请求审批，审批后执行并验证收敛"
  metadata = @{}
  scenarioId = "ordercare-floworder-v1"
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/api/agent/runs `
  -ContentType "application/json" `
  -Body $body
```

SSE Runtime 事件：

```powershell
curl.exe -N -X POST "http://localhost:8080/api/agent/runs/events" `
  -H "Content-Type: application/json" `
  -d '{"conversationId":"demo-sse-1","userId":"demo-user","question":"查询工单 T1001 的状态","metadata":{}}'
```

## 文档入口

- [项目总蓝图：OrderCare Incident Agent](docs/enterprise-agent-master-blueprint.md)
- [OrderCare 实施状态、学习地图与中间件清单](docs/ordercare-implementation-status.md)
- [OrderCare M2 受控恢复证据](docs/reports/ordercare/m2-controlled-recovery.md)
- [OrderCare M3 故障正确性与 20 条 Eval 证据](docs/reports/ordercare/m3-fault-correctness.md)
- [OrderCare × FlowOrder 早期设计记录（已被 V1.1 替代）](docs/ordercare-floworder-integration-design.md)
- [当前架构](docs/architecture.md)
- [构建与运行](docs/build-and-run.md)
- [API 使用](docs/api-guide.md)
- [Vue Agent 学习控制台](docs/frontend-learning-console.md)
- [学习顺序](docs/learning-guide.md)
- [面试讲法](docs/interview-guide.md)
- [设计决策](docs/design-decisions.md)
- [仍然存在的边界](docs/remaining-gaps.md)

## 重要边界

这是一个有真实工程深度的面试学习项目，不应描述成 Claude Code 或 OpenClaw 的等价实现。它尚未提供操作系统级 Sandbox、管理 API 身份认证、分布式任务队列、原生模型 Tool Calling 适配和逐 Token 的结构化流式解析。详见 [仍然存在的边界](docs/remaining-gaps.md)。
