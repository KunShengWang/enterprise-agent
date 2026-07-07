# V4.6 Streaming Agent

这一版新增真正的 Agent 事件流接口。

## 旧接口的问题

旧接口 `/api/agent/runs/stream` 更像是：

```text
先同步执行完整 Agent
再把 steps 和 answer 拆成 SSE 输出
```

它能让前端看到步骤，但不是完整的流式执行。

## 新接口

新接口 `/api/agent/runs/events` 使用 `StreamingAgentExecutor`：

```text
订阅 SSE
  -> run.started
  -> memory.loaded
  -> guardrail.input
  -> route.selected
  -> query.rewritten
  -> rag.retrieved / tool.planned / tool.executed
  -> prompt.assembled
  -> llm.started
  -> llm.token ...
  -> final / error
```

## 事件结构

```json
{
  "eventId": "...",
  "traceId": "...",
  "conversationId": "...",
  "type": "llm.token",
  "content": "模型片段",
  "createdAt": "...",
  "metadata": {}
}
```

## API

```http
POST /api/agent/runs/events
Content-Type: application/json
Accept: text/event-stream

{
  "conversationId": "conversation-1",
  "userId": "user-1",
  "question": "查询工单 T1001 的状态"
}
```

## 事件类型

| 类型 | 含义 |
| --- | --- |
| `run.started` | 一次 Agent Run 开始 |
| `memory.loaded` | 已加载短期和长期记忆 |
| `guardrail.input` | 输入安全检查结果 |
| `route.selected` | 路由到聊天、RAG 或工具调用 |
| `query.rewritten` | 查询改写结果 |
| `rag.retrieved` | RAG 检索完成 |
| `tool.registry` | 已加载可用工具 |
| `tool.planned` | 工具调用计划 |
| `tool.executed` | 工具执行结果 |
| `prompt.assembled` | Prompt 组装完成 |
| `llm.started` | 模型流式生成开始 |
| `llm.token` | 模型返回的 token 或文本片段 |
| `final` | 最终回答或护栏拦截结果 |
| `error` | 执行失败事件 |

## 面试解释

真正的流式 Agent 不只是模型 token 流，还要把 Agent 的中间过程事件化：

- Memory 加载事件
- Guardrail 检查事件
- 路由和计划事件
- RAG 检索事件
- 工具调用事件
- LLM token 事件
- 最终答案事件

这样前端可以展示类似“正在检索资料”“正在调用工具”“正在生成回答”的过程，后端也能把事件和 Trace 关联起来。

当前版本已经做到 LLM token 级流式输出；RAG 和 Tool 阶段是事件化输出。后续可以进一步把 RAG 检索和工具执行本身也改造成异步事件流。
