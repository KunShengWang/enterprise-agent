# V4.5 Multi-Agent

这一版新增一个独立的 Multi-Agent 编排入口。

## 角色

- `PLANNER`
  - 根据用户问题和 IntentRoute 拆解任务。

- `RAG_WORKER`
  - 负责知识库检索。

- `TOOL_WORKER`
  - 负责工具选择和执行。

- `REVIEWER`
  - 聚合 Planner、Worker、RAG 和 Tool 结果，生成最终回答。

## 主流程

```text
用户问题
  -> MultiAgentOrchestrator
  -> MemoryService.load()
  -> IntentRouter.route()
  -> Planner 生成任务
  -> RAG Worker / Tool Worker 执行
  -> Reviewer 聚合结果
  -> LLM 生成最终回答
```

## API

```http
POST /api/agent/multi-agent/runs
Content-Type: application/json

{
  "conversationId": "conversation-1",
  "userId": "user-1",
  "question": "查询工单 T1001 的状态"
}
```

## 面试解释

Multi-Agent 不是简单多调几次模型，而是把职责拆开：

- Planner 负责计划。
- Worker 负责执行特定能力。
- Reviewer 负责检查和聚合。

当前版本是轻量多 Agent，不引入复杂分布式调度；它复用已有 Memory、RAG、Tool、LLM 能力，便于解释和调试。

后续可以增强：

- 并行执行多个 Worker。
- Reviewer 对 Worker 结果打分。
- Multi-Agent Run 接入 Trace 和 Eval。
- 子 Agent 使用独立 Prompt 和独立 Memory。
