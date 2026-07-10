# Enterprise Agent

Enterprise Agent 是一个面向企业知识库和工单场景的轻量级自研 Agent 项目。

项目目标不是简单调用大模型，也不是把所有逻辑交给框架黑盒封装，而是用 Spring Boot / Spring AI 提供模型、流式输出等基础能力，项目自己掌握 Agent 编排、检索、工具调用、记忆、安全、评估和观测链路。

## 项目定位

```text
企业知识库 + 智能工单 Agent 平台
```

核心原则：

```text
框架负责能力，项目负责编排。
```

Spring AI 负责：

- ChatModel / ChatClient 基础模型调用
- 模型流式输出能力
- Tool Calling / VectorStore 等底层能力参考

项目自己负责：

- AgentExecutor 主执行链路
- IntentRouter / SkillSelector / PromptAssembler
- RAG 检索增强和 Rerank
- ToolRegistry / ToolExecutor / MCP 接入
- Memory 短期记忆、长期记忆、用户画像
- Guardrails / HITL
- Trace / Eval / AgentOps
- Agent Run / Workflow 状态机、checkpoint 与恢复执行
- Multi-Agent 协作编排
- Streaming Agent 事件流

## 当前能力

| 模块 | 当前实现 |
| --- | --- |
| Agent Core | `V1AgentExecutor` 串联 Memory、Guardrail、Skill、Route、RAG、Tool、LLM、Trace、Eval |
| RAG | PostgreSQL + pgvector、文档加载切分、Embedding、Hybrid Retrieval、Rerank、RAG Eval、RAG Run Report |
| Tool / MCP | 本地工单工具、JSON Schema 参数校验、工具运行记录、filesystem MCP、自研 ticket MCP server |
| Memory | 短期记忆、摘要压缩、长期记忆、用户画像；支持 JDBC 持久化和内存模式 |
| AgentOps / Trace | Run / Span / Replay / Stats，记录耗时、状态、失败原因、token 和成本估算 |
| Eval | 评测集、回归评测、关键词、RAG 命中、工具调用、groundedness、LLM-as-Judge 兜底 |
| Guardrails / HITL | Prompt Injection 检测、敏感信息脱敏、工具权限、高风险工具挂起审批、批准/拒绝和审计记录 |
| Reliable Run | Agent Run、执行计划和 checkpoint 持久化；审批后恢复；`toolCallId` 副作用幂等；未知结果进入人工兜底 |
| Workflow | 显式状态节点、执行计划、checkpoint、retryable / resumable 标记、真实恢复执行和查询接口 |
| Skills | Skill 注册、描述检索、工具绑定和默认任务能力 |
| Multi-Agent | Planner、RAG Worker、Tool Worker、Reviewer 角色协作和结果聚合 |
| Streaming | 结构化 SSE 事件流，输出 run、memory、guardrail、rag/tool、llm.token、final、error |

## 主流程

```text
用户问题
  -> Trace 开始
  -> 加载 Memory
  -> 输入 Guardrail
  -> Skill 选择
  -> 意图识别 / 路由
  -> Query Rewrite
  -> RAG / Tool / MCP / Clarify
  -> Prompt 组装
  -> LLM 调用或流式生成
  -> 输出 Guardrail
  -> 保存会话
  -> Eval / Trace / Workflow 记录
  -> 返回结果
```

更完整的图见 [架构说明](docs/architecture.md)。

高风险工具的可靠执行闭环：

```text
创建 Agent Run -> 持久化计划/checkpoint -> WAITING_APPROVAL
-> 人工批准或拒绝 -> 从同一 run/checkpoint 恢复
-> toolCallId 幂等执行 -> COMPLETED / REJECTED / MANUAL_REVIEW
-> Trace 回放和 Eval 验证
```

## 快速构建

```powershell
mvn -DskipTests package
```

如果 Maven 报 `NoClassDefFoundError: org/apache/http/client/HttpResponseException`，说明本机 Maven 运行时缺 Apache HttpClient 4.x jar，处理方式见 [构建与启动说明](docs/build-and-run.md)。

## 本地启动

配置真实模型和 Embedding：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
$env:DEEPSEEK_CHAT_MODEL="deepseek-chat"
$env:EMBEDDING_API_KEY="你的智谱 API Key"
```

配置 PostgreSQL / pgvector：

```powershell
$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="1234"
$env:MEMORY_MODE="jdbc"
```

启动：

```powershell
mvn spring-boot:run
```

健康检查：

```powershell
curl.exe http://localhost:8080/api/agent/health
```

完整启动、MCP 和 pgvector 准备步骤见 [构建与启动说明](docs/build-and-run.md)。

## 演示入口

核心演示接口见 [演示接口文档](docs/demo-api-guide.md)。

建议演示顺序：

1. 健康检查和路由预览
2. RAG 文档入库、检索和问答
3. 工具调用和工具运行记录
4. 高风险工具触发 HITL 审批
5. Prompt Injection Guardrail 拦截
6. Streaming SSE 事件流
7. Trace / Workflow / Eval 查询
8. Multi-Agent 协作调用

## 关键文档

- [架构说明](docs/architecture.md)
- [演示接口文档](docs/demo-api-guide.md)
- [构建与启动说明](docs/build-and-run.md)
- [自研 Agent 项目执行路线](docs/self-agent-roadmap.md)
- [API Key 配置说明](docs/api-keys.md)
- [V2.0 PostgreSQL + pgvector RAG](docs/v2-0-pgvector-rag.md)
- [V3.0 Tool Calling / MCP](docs/v3-0-tool-mcp.md)
- [V3.2 Memory](docs/v3-2-memory.md)
- [V4.0 AgentOps / Trace](docs/v4-0-agentops-trace.md)
- [V4.1 Eval](docs/v4-1-agent-eval.md)
- [V4.2 Guardrails / HITL](docs/v4-2-guardrails-hitl.md)
- [V4.3 Workflow](docs/v4-3-workflow-state-machine.md)
- [V4.4 Skills](docs/v4-4-skills.md)
- [V4.5 Multi-Agent](docs/v4-5-multi-agent.md)
- [V4.6 Streaming Agent](docs/v4-6-streaming-agent.md)
- [V4.9 Reliable Agent Run](docs/v4-9-reliable-agent-run.md)

## 面试可讲点

- 手写 Agent 编排引擎，核心链路可控、可观测、可评估。
- RAG 不只做向量检索，还实现了混合检索、Rerank、命中评估和运行报告。
- Tool 和 MCP 统一进入 ToolRegistry，工具参数校验、执行日志和审批链路完整。
- Memory 不只是最近消息，还包含摘要、长期记忆和用户画像。
- AgentOps 覆盖 Trace、Replay、Eval、Workflow、成本估算和失败原因。
- Guardrails / HITL 体现企业场景中安全、权限和审计问题。
- 高风险副作用不会在审批请求阶段执行；批准后以原 `toolCallId` 恢复，重复恢复不会重复执行副作用。
- 工具返回后若执行结果无法可靠落库，Run 进入 `MANUAL_REVIEW`，避免盲目重试造成二次副作用。

## 已知风险

这些风险来自代码审查和当前实现边界，后续优化优先级高于继续堆新功能：

- Agent Run 和 Tool Execution 的 JDBC 表目前由应用自初始化，生产部署仍应迁移到 Flyway/Liquibase 管理。
- RAG 还没有 Redis 缓存和压测报告。
- Multi-Agent 当前是轻量顺序编排，不是真并行调度。
- Token / 成本优先使用模型 provider usage，provider 不返回时仍会退化为估算。
- Streaming 中 LLM token 是真流式，RAG / Tool 阶段是事件化输出。
- 当前恢复点聚焦高价值的高风险工具审批场景，不是任意节点通用 DAG 调度器。
