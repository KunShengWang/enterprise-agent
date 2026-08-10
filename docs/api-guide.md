# API 使用指南

> 实现基线：`b6207a4`。统一产品入口和直接 Runtime 调试入口并存，但语义不同。

所有普通业务响应使用 `ApiResponse<T>` 包装。当前 Workbench 身份来自服务端 `WorkbenchPrincipalProvider`；本地实现是演示身份，不是生产认证。

## 1. 推荐入口：Unified Workbench

### 提交自然语言输入

```http
POST /api/agent/conversations/{conversationId}/inputs
Idempotency-Key: <clientInputId>
Content-Type: application/json

{
  "content": "介绍 Spring Boot IoC",
  "metadata": {}
}
```

响应为 `202 Accepted`，返回 `inputId` 和 `workItemId`。接口先持久化输入、区分 WorkCommand 和新目标；新目标在后台异步路由和派发，不等待最终回答。

身份、tenant、role、ExecutionTarget、runId 等字段不能由 Body metadata 伪造。

### 查询 Conversation 和 WorkItem

```http
GET /api/agent/conversations/{conversationId}/inputs
GET /api/agent/conversations/{conversationId}/work-items?limit=100
GET /api/agent/conversations/{conversationId}/focus

GET /api/agent/work-items/{workItemId}
GET /api/agent/work-items/{workItemId}/events?afterSequence=-1&limit=500
GET /api/agent/work-items/{workItemId}/execution-tree
GET /api/agent/work-items/{workItemId}/budget
```

WorkItem Detail 是控制、执行和结果状态的权威产品视图。

### 用户可读 Presentation

```http
GET /api/agent/work-items/{workItemId}/presentations?afterSequence=-1&limit=500
GET /api/agent/work-items/{workItemId}/presentations/inspector?afterSequence=-1&limit=500
GET /api/agent/work-items/{workItemId}/presentations/stream?afterSequence=-1
Accept: text/event-stream
```

- 普通 Presentation：中间 Conversation Timeline 的安全公开内容；
- Inspector Presentation：技术检查器数据；
- 原始 payload、Prompt 和隐藏推理不得放入普通 Presentation。

### 统一事件流

```http
GET /api/agent/work-items/{workItemId}/events/stream?afterSequence=-1&afterRunSequence=-1
Accept: text/event-stream
```

复合 cursor 同时跟踪 WorkEvent 和 Primary Run Runtime Event。只允许 Primary Run 的 `MODEL_DELTA` 进入主回答，Child Run delta 由执行树/Inspector 隔离。客户端需要按 eventId 去重并在断线后使用最后 cursor 重连。

### 路由 Preview 确认

危险目标（当前包括 Incident Investigation）使用 Preview → Explicit Confirmation：

```http
POST /api/agent/work-items/{workItemId}/confirm-route
Content-Type: application/json

{
  "previewId": "...",
  "previewVersion": 1,
  "validatedInputDigest": "...",
  "scopeDigest": "...",
  "clientInputId": "confirm-..."
}
```

拒绝：

```http
POST /api/agent/work-items/{workItemId}/reject-route
Content-Type: application/json

{
  "previewId": "...",
  "clientInputId": "reject-..."
}
```

前端应使用 WorkItem Detail/Presentation 返回的实际 Preview 字段，不能自己构造 digest。

### 控制 WorkItem

```http
POST /api/agent/work-items/{workItemId}/pause
POST /api/agent/work-items/{workItemId}/resume
POST /api/agent/work-items/{workItemId}/cancel
POST /api/agent/work-items/{workItemId}/abandon
Content-Type: application/json

{
  "expectedVersion": 12,
  "clientInputId": "command-..."
}
```

通用形式也可用：

```http
POST /api/agent/work-items/{workItemId}/commands/{command}
```

`expectedVersion` 提供 CAS，`clientInputId` 提供命令幂等。`ABANDON` 表示 Workbench 不再控制/关注，不等于一定取消底层副作用；`CANCEL` 才请求终止底层执行。

自然语言“继续、终止、补充信息或开始新任务”也通过统一 `/inputs` 入口，由 WorkCommandClassifier 判断，不需要调用方自己选择 ExecutionTarget。

## 2. 直接 Runtime API（学习与调试）

```http
POST /api/agent/runs
Content-Type: application/json

{
  "conversationId": "session-001",
  "userId": "user-001",
  "question": "介绍 Tool Calling",
  "metadata": {},
  "scenarioId": ""
}
```

发送 `Accept: text/event-stream` 使用同一 Runtime 的 SSE 适配器。直接 Runtime API 绕过 WorkItem、统一路由和 Dispatch，因此不是普通产品入口。

查询与持久化事件：

```http
GET /api/agent/runs?limit=20
GET /api/agent/runs/{runId}
GET /api/agent/runs/{runId}/events?afterSequence=42&limit=500
GET /api/agent/conversations/{conversationId}/messages
```

控制与恢复：

```http
POST /api/agent/runs/{runId}/pause
POST /api/agent/runs/{runId}/cancel
POST /api/agent/runs/{runId}/resume
POST /api/agent/runs/{runId}/resume/events
Accept: text/event-stream
```

Resume 参数必须是 `runId`，不能传 `runId:leaseOwnerUuid`。恢复不会创建新 Run；Context/Model 阶段从完整消息边界重新决策，`EXECUTING_TOOL` 阶段先查询或对账原 ToolExecution。

## 3. Approval

```http
GET /api/agent/guardrails/approvals
GET /api/agent/guardrails/approvals/{approvalId}

POST /api/agent/guardrails/approvals/{approvalId}/decide
Content-Type: application/json

{
  "approved": true,
  "reviewer": "local-reviewer",
  "reason": "已核对预演版本和影响范围"
}
```

审批等待不阻塞原线程。决定后使用 WorkItem resume 或直接 Runtime resume（取决于入口）继续原执行。Approval 过期、Proposal 漂移或版本不一致时必须重新 Preview/Approval。

## 4. Incident 高级接口

统一产品入口优先使用 Workbench。以下接口用于测试和高级观测：

```http
POST /api/incidents/investigate
GET  /api/incidents/{incidentId}
GET  /api/incidents/{incidentId}/events?afterSequence=-1&limit=500
GET  /api/incidents/{incidentId}/events/stream?afterSequence=-1
GET  /api/incidents/{incidentId}/trace
```

Recovery Plan：

```http
POST /api/incidents/{incidentId}/recovery-plans
GET  /api/incidents/{incidentId}/recovery-plans
GET  /api/incidents/{incidentId}/recovery-plans/{planId}
POST /api/incidents/{incidentId}/recovery-plans/{planId}/items/{itemId}/decision
```

Phase 3 运维：

```http
GET  /api/incidents/phase3/status
POST /api/incidents/phase3/scan
```

这些 Controller 不是自动暴露给模型的 Capability。模型只能调用 Registry 和 Profile 明确允许的 Tool。

## 5. RAG、Memory、Capability

RAG：

```http
POST /api/agent/rag/ingest
POST /api/agent/rag/index
POST /api/agent/rag/search
GET  /api/agent/rag/stats
GET  /api/agent/rag/cache/stats
GET  /api/agent/rag/runs
```

Memory：

```http
GET  /api/agent/memory/conversations/{conversationId}/recall
GET  /api/agent/memory/users/{userId}/profile
POST /api/agent/memory/users/{userId}/profile
```

Capability 与执行证据：

```http
GET /api/agent/tools
GET /api/agent/tools/executions?runId={runId}
GET /api/agent/tools/executions/{toolCallId}
GET /api/agent/skills
GET /api/agent/skills/{name}
```

项目没有“任意工具直接执行”HTTP API；副作用必须经过 Runtime 或确定性 Recovery Plan 协调器。

## 6. Trace、Eval 与 AgentOps

```http
GET /api/agent/traces
GET /api/agent/traces/{traceId}
GET /api/agent/traces/{traceId}/replay
GET /api/agent/ops/summary
GET /api/agent/ops/evidence
GET /api/agent/evals/reports
GET /api/agent/evals/events
```

Trace/Eval/Presentation 是权威 Runtime/Work/Incident 事实的投影，不应维护另一套状态机。

## 7. 安全边界

- 当前未内建生产认证，管理接口必须由可信网关或 Spring Security 保护；
- 本地 Principal 只用于开发演示；
- 不允许客户端通过 metadata 提交可信身份、角色、ExecutionTarget 或内部 ID 来源；
- 不允许把 Controller 当作模型 Capability；
- 不允许把 HTTP 超时解释为副作用未执行。
