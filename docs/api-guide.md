# API 使用指南

所有业务响应使用 `ApiResponse<T>` 包装。以下示例省略了外部网关认证；当前项目本身未实现身份认证，部署时必须由网关或 Spring Security 保护管理接口。

## 1. 运行 Agent

```http
POST /api/agent/runs
Content-Type: application/json

{
  "conversationId": "session-001",
  "userId": "user-001",
  "question": "查询工单 T1001 的状态",
  "metadata": {
    "tenantId": "demo",
    "roles": ["USER"]
  }
}
```

相同 `conversationId` 的请求共享有序消息时间线；同一 Session 同时只能有一个持有租约的 Run。

查询 Run：

```http
GET /api/agent/runs/{runId}
GET /api/agent/runs?limit=20
```

可恢复暂停与永久取消是两种不同语义：

```http
POST /api/agent/runs/{runId}/pause
POST /api/agent/runs/{runId}/cancel
```

暂停先把状态写为 `PAUSE_REQUESTED`，Runtime 到达持久化安全边界后写为 `PAUSED`；取消最终收敛为不可恢复终态。

## 2. SSE Runtime 事件

```http
POST /api/agent/runs
Accept: text/event-stream
Content-Type: application/json
```

事件包括：Run 开始/暂停请求/已暂停/恢复/结束、Context 投影/压缩、模型开始/正文增量/完成/失败、工具请求、策略判定、审批等待、工具开始/结束、Sub-Agent 开始/结束和心跳。`MODEL_DELTA` 来自 Provider 原生流式响应，经输出 Guardrail 与合并后持久化；ToolCall JSON 不会混入回答。持久事件包含 `sequence`；心跳携带 `lastPersistedSequence`。收到 `stream_gap` 时应停止消费并按最后序号重新加载持久事件。

```http
GET /api/agent/runs/{runId}/events?afterSequence=42&limit=500
```

该接口从 PostgreSQL 返回序号大于 `afterSequence` 的事件，用于 SSE 断线或 `stream_gap` 后补拉。

`/runs/stream` 是兼容的字符串事件视图；`POST /runs/events` 是结构化 SSE 的兼容别名。新调用方优先对 `POST /runs` 发送 `Accept: text/event-stream`。

## 3. 审批与恢复

当 Tool Policy 返回 `ask` 时，Run 状态为 `WAITING_APPROVAL`：

```http
GET /api/agent/guardrails/approvals
GET /api/agent/guardrails/approvals/{approvalId}
```

单条与列表查询都经过 `ApprovalService` 的生命周期收敛；超过 `expiresAt` 但仍持久化为 `REQUESTED` 的记录会先通过数据库 CAS 转为 `EXPIRED`，再返回给客户端。

审批：

```http
POST /api/agent/guardrails/approvals/{approvalId}/decide
Content-Type: application/json

{
  "approved": true,
  "reviewer": "interviewer-demo",
  "reason": "已核对工单影响范围"
}
```

恢复同一个 Run：

```http
POST /api/agent/runs/{runId}/resume
```

若调用方需要把审批后的执行过程继续展示在原事件时间线中，使用结构化 SSE 恢复接口：

```http
POST /api/agent/runs/{runId}/resume/events
Accept: text/event-stream
```

该接口只是 `AgentRuntime.resume(runId, listener)` 的流式适配，不包含另一套恢复逻辑。初次执行与恢复执行产生的持久化事件共享同一个 `runId` 和递增 `sequence`。

恢复不会创建新 Run。Runtime 会原子 claim `WAITING_APPROVAL` 或 `PAUSED` 的原 Run，从持久化 Profile、BudgetSnapshot、Phase、pending ToolCall 和已完成 ToolResult 继续；抢占失败者只返回当前状态。Context/Model 阶段从最后一条完整消息边界重新进行本轮模型决策；`EXECUTING_TOOL` 阶段先使用原 toolCallId 查询或对账持久化执行结果，禁止盲目重复副作用。

## 4. RAG

```http
POST /api/agent/rag/ingest
POST /api/agent/rag/index
POST /api/agent/rag/search
Content-Type: application/json

{"query":"发布失败如何回滚","topK":3}
```

运维查询：

```http
GET /api/agent/rag/stats
GET /api/agent/rag/cache/stats
GET /api/agent/rag/runs
GET /api/agent/rag/runs/stats
DELETE /api/agent/rag/cache
```

删除语料会同时失效 PostgreSQL RAG 缓存：

```http
DELETE /api/agent/rag/source
Content-Type: application/json

{"source":"release-process.md"}
```

## 5. 长期记忆

Runtime 自动对用户消息执行结构化长期记忆提取。短期消息不通过 Memory API 写入，而是由 `AgentTimelineStore` 保存。

```http
GET /api/agent/memory/conversations/{conversationId}/recall?userId=user-001&query=回答偏好&limit=8
GET /api/agent/memory/users/{userId}/profile
POST /api/agent/memory/users/{userId}/profile
DELETE /api/agent/memory/conversations/{conversationId}
DELETE /api/agent/memory/users/{userId}
```

手工画像写入示例：

```json
{"key":"answer_style","value":"简洁中文","source":"manual"}
```

## 6. Skill 与工具目录

```http
GET /api/agent/skills
GET /api/agent/skills/{name}
GET /api/agent/tools
GET /api/agent/tools/executions?runId={runId}
GET /api/agent/tools/executions/{toolCallId}
```

没有直接工具执行接口。所有副作用必须通过 Agent Runtime，确保权限、审批、预算和幂等语义不被绕开。

## 7. Trace、Eval 与 AgentOps

```http
GET /api/agent/traces
GET /api/agent/traces/{traceId}
GET /api/agent/traces/{traceId}/replay
GET /api/agent/ops/summary
GET /api/agent/ops/evidence
GET /api/agent/evals/reports
GET /api/agent/evals/events
```

Trace/Eval 是 Runtime 事件的投影，不是另一套手工埋点流程。

## 8. Multi-Agent

```http
POST /api/agent/multi-agent/runs
Content-Type: application/json

{
  "conversationId":"parent-session",
  "userId":"user-001",
  "question":"结合知识库与工单状态给出故障处理建议",
  "metadata":{}
}
```

Planner、Specialist 和 Reviewer 各自生成 child Run。响应只返回任务摘要、审查结果和关联标识，不把所有子上下文合并进主对话。
