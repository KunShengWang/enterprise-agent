# V1.3 地基加固

## 这一版解决什么

V1.3 不引入真实向量库，也不扩展复杂业务功能。

目标是先把单 Agent 主链路跑稳：

```text
真实 LLM 调用失败时不暴露底层异常
RAG / Tool / LLM 关键步骤记录耗时
路由结果可独立验证
WebFlux 中的同步 Agent 执行切到 boundedElastic
本地审批策略去掉明显 mock 命名
```

## 新增路由预览

接口：

```text
POST /api/agent/routes/preview
```

示例：

```json
{
  "conversationId": "conversation-1",
  "userId": "user-1",
  "question": "你好？"
}
```

预期：

```text
CHAT
```

这用于验证请求进入 Agent 后会先走哪个分支：

```text
CHAT
TOOL
RAG
CLARIFY
```

## 模型异常处理

模型调用失败时，`SpringAiLlmService` 会把底层异常包装成：

```text
LlmCallException
```

`V1AgentExecutor` 只向用户返回安全文案，不再把 HTTP、线程、堆栈等底层细节直接拼到回答里。

Trace 中会记录：

```text
llm.call [FAILED] errorType=MODEL_CALL_FAILED, durationMs=...
```

## 耗时记录

当前已在 step summary 中记录：

```text
rag.retrieve durationMs
tool.execute durationMs
llm.call durationMs
```

后续 V2 做 AgentOps 时，这些内存记录会升级为持久化模型调用日志、检索日志和工具调用日志。

## 为什么还不进真实 RAG

因为真实 RAG 会同时引入：

```text
文档解析
切分
Embedding
PostgreSQL + pgvector
召回评估
引用溯源
```

如果主链路和模型调用还不稳定，直接进入 RAG 会把问题混在一起。

V1.3 的目的就是先保证：

```text
请求能稳定进入 Agent
路由结果可解释
模型异常可控
Trace 能看清关键步骤
```
