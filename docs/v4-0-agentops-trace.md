# V4.0 AgentOps / Trace

这一版把 Trace 从简单事件列表升级为 AgentOps 运行记录。

## 学习目标

AgentOps / Trace 解决的问题是：一次 Agent 为什么这么回答、每一步花了多久、调用了哪些能力、哪里失败、能不能回放。

## 数据结构

- `TraceRun`
  - 一次完整 Agent 执行。
  - 包含 `traceId`、`conversationId`、问题、状态、总耗时、失败原因、token/成本估算、spans、events、replayEvents。

- `TraceSpan`
  - 一次阶段执行。
  - 例如 `memory.load`、`guardrail.input`、`rag.retrieve`、`tool.execute`、`llm.call`。
  - 包含 kind、status、durationMs、input/output/error、attributes。

- `TraceReplayEvent`
  - 可回放事件。
  - 用于按时间顺序解释 Agent 执行过程。

- `TraceRunStats`
  - 统计最近 N 次运行的成功数、失败数、阻断数、平均耗时、估算 token 和成本。

## 主流程接入

```text
AgentController
  -> V1AgentExecutor.execute()
  -> TraceRecorder.start()
  -> 每个阶段 addStep()
  -> TraceRecorder.recordSpan()
  -> LLM 调用后记录估算 token 和成本
  -> TraceRecorder.markStatus()
  -> TraceRecorder.finish()
```

## API

查看最近 Trace：

```http
GET /api/agent/traces?limit=20
```

查看单个 Trace：

```http
GET /api/agent/traces/{traceId}
```

回放一次 Agent 执行：

```http
GET /api/agent/traces/{traceId}/replay
```

查看统计：

```http
GET /api/agent/traces/stats?limit=100
```

## 面试解释

这一版的 Trace 不是普通日志，而是结构化 AgentOps 数据：

1. Run 层看一次 Agent 总体结果。
2. Span 层看 Memory、RAG、Tool、LLM、Guardrail 等阶段。
3. Replay 层按顺序复盘 Agent 做过什么。
4. Metrics 层记录耗时、token 和成本估算。

当前 token 和成本是估算值，因为底层 `LlmService` 还没有统一暴露真实 usage。后续如果模型返回 usage，可以替换估算逻辑。
