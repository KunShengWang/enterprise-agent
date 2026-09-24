# API 使用指南

> 当前采购版接口说明。统一产品入口与受限 Runtime 调试入口并存；历史报告不代表当前路由注册。

所有普通业务响应使用 `ApiResponse<T>` 包装。当前 Workbench 身份来自服务端 `WorkbenchPrincipalProvider`；本地实现是演示身份，不是生产认证。

## 1. 推荐入口：Unified Workbench

### 提交自然语言输入

```http
POST /api/agent/conversations/{conversationId}/inputs
Idempotency-Key: <clientInputId>
Content-Type: application/json

{
  "content": "为研发团队采购 5 台工作站，预算 10 万元，交期两周，请比较候选",
  "metadata": {}
}
```

响应为 `202 Accepted`，返回 `inputId` 和 `workItemId`。接口先持久化输入、区分 WorkCommand 和新目标；新目标在后台异步路由和派发，不等待最终回答。

身份、tenant、role、ExecutionTarget、runId 等字段不能由 Body metadata 伪造。

### 会话所有权与历史兼容

工作台保留客户端提供的原 conversationId，但该 ID 必须由服务端可信 tenant/principal 独占。服务端使用同一 PostgreSQL 数据源中的 `agent_public_conversation_owner` 表原子登记全局 Timeline ID 所有权；并发不同身份只能有一个登记成功，其他请求返回 HTTP 403。工作台拒绝 `procurement-api-` 保留前缀。

已有会话只有在工作台/采购 Case 记录能证明唯一的 tenant/user 归属且 Timeline user 一致时才允许继续。存在多个历史所有者、归属冲突或只有 userId 无法证明租户的历史 Timeline，均失败关闭，留待人工核实；不会迁移、重命名或覆写历史消息。合法用户的原 ID、Case 和上下文保持不变。

`GET /api/agent/conversations/{conversationId}/messages` 同样校验归属；其他身份返回 HTTP 403，完全未知的会话返回空数组，不读取 Timeline。历史 Run 查询和既有恢复策略不在本次改造范围内。

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

复合 cursor 同时跟踪 WorkEvent 和 Primary Run Runtime Event。只允许 Primary Run 的 `MODEL_DELTA` 进入主回答，Child Run delta 不进入主回答；这不表示当前已实现采购 Child Run 执行树。客户端需要按 eventId 去重并在断线后使用最后 cursor 重连。

### 路由 Preview 确认

通用 RoutePreview 确认机制继续保留。只有当前有效目标及有效 Preview 才能确认；旧 Incident/Scope Preview 一律退役拒绝。RFQ 使用独立 HITL 审批，不能由路由确认代替：

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

## 2. 直接采购 Run API

```http
POST /api/agent/runs
Content-Type: application/json

{
  "conversationId": "session-001",
  "question": "你能帮我做哪些采购工作？",
  "metadata": {},
  "scenarioId": "procurement-sourcing-rfq-v1"
}
```

发送 `Accept: text/event-stream` 使用同一 Runtime 的 SSE 适配器。`POST /runs/events`（结构化 SSE）及 `/runs/stream`（文本 SSE）采用相同的新建约束。

- `scenarioId` 缺省、null 或空白：服务端绑定 `procurement-sourcing-rfq-v1`；显式采购 Profile 同样接受。
- 显式 `general-agent-v1`、`main-agent` 或其他非采购 Profile：HTTP 400 / `BAD_REQUEST`，不创建 Case 或 Run，不回退。
- 退役场景、退役执行目标或退役业务问题：仍为 HTTP 410 / `TARGET_RETIRED`。
- `userId` 和全部客户端 metadata 不作为执行参数。身份、tenant、roles 来自服务端 `WorkbenchPrincipalProvider`，Case 由 `ProcurementCaseService.ensureCase` 初始化，Case ID/version 由服务端填充。空 Case 不代表已经形成采购需求。
- `conversationId` 是当前身份下的会话别名，服务端映射为隔离的采购 API 会话 ID。后续请求可以重复使用原别名或响应中的有效会话 ID；查询消息时使用响应中的有效 ID。缺省时生成新会话。其他身份的有效 ID 或已存在且属于其他身份的别名返回 HTTP 403，不会静默改成新会话。
- 使用可信 tenant/user 限流；身份、Profile 或 Case 初始化失败则不执行 Runtime。直接 API 仍不创建 WorkItem，不提供工作台级幂等、预算与派发，正式产品请继续使用 `/inputs`。

旧 `POST /api/agent/multi-agent/runs` 以及 `/api/agent/evals/run`、`/regression`、`/adversarial` 统一返回 HTTP 400 / `BAD_REQUEST`，不再公开启动通用 Agent。内部 Multi-Agent 和 EvalRunner 保留，评测 Case 管理、报告与事件查询保留。内部测试/Profile 执行不受此 HTTP 策略影响。

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

Resume 参数必须是 `runId`，不能传 `runId:leaseOwnerUuid`。本阶段保留历史 Generic/空 Profile 的既有恢复策略，不转换为采购 Profile。恢复不会创建新 Run；Context/Model 阶段从完整消息边界重新决策，`EXECUTING_TOOL` 阶段先查询或对账原 ToolExecution。

## 3. Approval

```http
GET /api/agent/guardrails/approvals
GET /api/agent/guardrails/approvals/{approvalId}

POST /api/agent/guardrails/approvals/{approvalId}/decide
Content-Type: application/json

{
  "approved": true,
  "reviewer": "local-reviewer",
  "reason": "已核对本次 RFQ 的供应商、数量和要求"
}
```

审批等待不阻塞原线程。决定后使用 WorkItem resume 或直接 Runtime resume（取决于入口）继续原执行。RFQ 审批过期、Case 版本变化或 Finalize 证据失效时应重新校验并申请审批；不能将路由 Preview 当作 RFQ 授权。

## 4. 退役业务

旧 Incident Controller 已删除，/api/incidents 不再是可用业务 API。旧历史从通用 WorkItem/WorkEvent/Run/Trace/审批读取；旧目标、工具、Preview 与恢复明确拒绝，不转换为采购或 RFQ。

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

项目没有“任意工具直接执行”HTTP API；副作用必须经过受限 Runtime 与审批边界；RFQ 当前使用模拟网关，不创建真实采购订单。

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

Trace/Eval/Presentation 是权威 Runtime/Work 事实的投影，不应维护另一套状态机。

## 7. 安全边界

- 当前未内建生产认证，管理接口必须由可信网关或 Spring Security 保护；
- 本地 Principal 只用于开发演示；
- 不允许客户端通过 metadata 提交可信身份、角色、ExecutionTarget 或内部 ID 来源；
- 不允许把 Controller 当作模型 Capability；
- 不允许把 HTTP 超时解释为副作用未执行。
