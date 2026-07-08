# V4.3 Workflow / State Machine

这一版把 Agent 执行链路显式建模成 Workflow。

## 为什么需要 Workflow

如果所有逻辑都写在一个 `V1AgentExecutor` 方法里，执行过程虽然能跑，但不容易解释：

- 当前执行到了哪个节点？
- 为什么走 RAG / Tool / Chat？
- 哪些节点可以重试？
- 哪些节点可以中断后恢复？
- 一次运行的计划是什么？

Workflow 的目标是把 Agent 执行从“代码顺序”升级成“可观测状态机”。

## 核心模型

- `WorkflowNode`
  - 状态机节点。
  - 例如 `LOAD_MEMORY`、`ROUTE_INTENT`、`RAG_RETRIEVE`、`TOOL_EXECUTE`、`LLM_CALL`。

- `WorkflowTransition`
  - 节点转移。
  - 例如 `ROUTE_INTENT -> RAG_RETRIEVE`，条件是 `route=RAG`。

- `WorkflowExecutionPlan`
  - 一次执行前生成的计划。
  - 包含节点列表、转移列表、是否可中断、是否可恢复。

- `WorkflowCheckpoint`
  - 运行中的节点记录。
  - 包含节点、状态、摘要、是否可重试、是否可恢复。

- `WorkflowRunRecord`
  - 一次完整 Workflow 运行记录。

## 当前接入方式

```text
V1AgentExecutor
  -> intent.route
  -> WorkflowPlanner.plan()
  -> WorkflowRecorder.start()
  -> 每个 addStep()
  -> WorkflowRecorder.checkpoint()
  -> finish()
  -> WorkflowRecorder.finish()
```

这一版没有强行重写所有业务逻辑，而是先把现有链路映射为状态机记录。这样风险较低，同时已经具备面试可解释性。

## API

查看最近 Workflow：

```http
GET /api/agent/workflows?limit=20
```

查看单次 Workflow：

```http
GET /api/agent/workflows/{traceId}
```

## 面试解释

Workflow 的价值是把 Agent 从“一段过程代码”变成“有计划、有状态、有 checkpoint 的执行系统”。

当前版本已经具备：

- 明确节点。
- 分支路由。
- 执行计划记录。
- checkpoint 记录。
- 节点级 retryable / resumable 标记。

当前已经新增 `/api/agent/workflows/{traceId}/resume`。它会读取最近一个 `resumable=true` 的 checkpoint，生成恢复计划，明确：

- 从哪个节点恢复；
- 哪些节点已完成可以跳过；
- 哪些节点需要继续执行；
- 为什么不能恢复。

恢复接口会记录 `RESUME_REQUESTED` checkpoint，并把 Workflow 状态标记为 `RESUMABLE`。为了避免重复执行工具和 LLM 带来的副作用，当前版本先生成恢复计划，不直接重放工具调用；后续可以在执行器层根据这个计划实现真正的节点级继续执行。
