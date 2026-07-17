# Vue Agent 学习控制台

## 目的

这个前端不是面向最终用户的聊天壳，而是用来学习后端的 Runtime Console。它把散落在 Controller、Store 和 Runtime 中的状态投影到一个页面中，帮助你回答：

- 一个请求怎样进入统一 Agent Runtime？
- 上下文、模型、工具、权限和审批按照什么顺序发生？
- SSE 实时事件与 PostgreSQL 持久化记录是什么关系？
- Run 为什么暂停、恢复、失败或进入人工复核？
- RAG、Memory、Skill、Trace 和 Eval 在主循环之外怎样独立验证？

## 启动

先启动 PostgreSQL/pgvector 和 Spring Boot 后端，确认：

```text
GET http://localhost:8080/api/agent/health
```

然后启动前端：

```powershell
cd frontend
npm install
npm run dev
```

浏览器访问：

```text
http://127.0.0.1:5173
```

Vite 会把 `/api` 请求代理到 `http://localhost:8080`，开发时不需要修改后端 CORS。

生产构建校验：

```powershell
cd frontend
npm run build
```

## 推荐学习顺序

### 1. Agent 运行台

发起一个普通问题，观察：

```text
RUN_STARTED
-> CONTEXT_PREPARED / CONTEXT_COMPACTED
-> MODEL_STARTED
-> MODEL_COMPLETED
-> TOOL_REQUESTED（可选）
-> POLICY_DECIDED（可选）
-> APPROVAL_REQUIRED（可选）
-> TOOL_COMPLETED（可选）
-> 再次进入 CONTEXT 与 MODEL
-> RUN_COMPLETED / RUN_FAILED / RUN_CANCELLED
```

页面首次执行使用 `POST /api/agent/runs/events`；审批后使用 `POST /api/agent/runs/{runId}/resume/events`。两条接口都通过 `fetch + ReadableStream` 解析 POST SSE，并汇入同一个 Run 工作区和同一条持久化 `sequence` 时间线。

注意：当前 `JsonAgentModelGateway` 使用结构化整轮模型调用，Runtime 尚未发布真正的逐 Token `MODEL_DELTA`。所以现在实时流式的是执行事件，最终文本主要在 `RUN_COMPLETED` 中收口；前端已经兼容未来的 `MODEL_DELTA`，但不会把客户端打字机动画伪装成模型原生流式。

运行台 URL 会保存 `runId`。刷新页面或从 Run 历史、审批中心进入时，前端会先读取 `AgentRunRecord + AgentEvent` 恢复工作区；若状态为 `WAITING_APPROVAL`，可直接在右侧审批卡片中决定并继续流式执行。

使用 `ordercare-floworder-v1` 场景时，同一工作区还会投影 Case、Proposal、Approval、Action 和 Convergence。M3 故障结果会显示 `responseLost`、`reconciled`、对账次数、是否按原 ID 补发以及 `recoveredAfterCrash`；这些字段来自 ToolResult/FlowOrder 权威状态，不由前端猜测。

### 2. Run 历史与回放

选择刚才的 Run，对照以下三类数据：

- `AgentRunRecord`：当前状态、阶段、预算、待审批 ToolCall 和最终结果。
- `AgentEvent`：按 sequence 排序的事实时间线。
- `ToolExecutionRecord`：工具副作用的幂等执行状态。

这一步对应后端的 `AgentRunStore`、`AgentTimelineStore` 和 `ToolExecutionStore`。

### 3. 审批中心

使用运行台中的“触发审批”示例，观察高风险 ToolCall 如何暂停。审批中心只负责定位待办和查看审计信息：

1. 查看工具名、参数、原因与有效期。
2. 点击“进入运行台处理”回到对应 `runId`。
3. 在运行台填写审批人与理由，并批准或拒绝。
4. 前端调用 `/runs/{runId}/resume/events`，继续接收审批后的工具与模型事件。
5. 刷新页面或回到 Run 历史，确认预算、Profile 和事件序号连续。

### 4. 能力地图

- Tool 是模型可请求的结构化能力，包含名称、描述、风险和 inputSchema。
- Skill 是注入上下文的方法说明，不等同于可执行 Tool。
- 工具运行记录和统计是执行证据，不是注册表定义。

### 5. RAG 与 Memory

- RAG 实验绕过 Agent Loop，直接验证检索、阈值、TopK、Rerank 和 Cache。
- Memory 实验按 conversationId、userId 和 query 召回长期记忆，并可查看结构化 UserProfile。

### 6. Trace、Eval 与 AgentOps

- Trace：一次 Run 的 Span、事件、Token、成本和耗时。
- Replay：根据持久化事件重建执行过程。
- Eval：回归用例、对抗用例以及结果指标。
- AgentOps：聚合 Trace、RAG、Tool、Cache 和 Eval 的工程证据。

### 7. 接口实验室

接口实验室收录当前所有 Controller 路由，可以编辑 Path、Query 与 JSON Body，并查看原始 `ApiResponse` 或 SSE。会创建 Run、调用模型、修改或删除数据的接口必须先勾选副作用确认。

## 前端目录

```text
frontend/src
├─ api
│  ├─ http.ts          统一 ApiResponse 与错误处理
│  ├─ stream.ts        POST SSE 解析器
│  ├─ agent.ts         业务 API 封装
│  └─ catalog.ts       全量接口目录
├─ composables
│  └─ useAgentStream.ts 运行台状态与事件收敛
├─ components          时间线、状态、JSON 与页面说明
├─ views               七个学习模块页面
├─ router.ts
└─ styles.css
```

## 常见错误

- `Failed to initialize rate limit schema`：通常是 PostgreSQL 未启动，或 `RAG_POSTGRES_PASSWORD` 没有被当前 Java 进程读取。
- 健康检查成功但 Run 失败：健康接口不访问数据库和模型，应继续查看页面错误或后端异常日志。
- RAG 返回 401：Embedding API Key 无效；Agent Mock 模式不代表外部 Embedding 自动切换为 Mock。
- 页面显示后端未连接：确认 8080 后端已启动，并使用 `npm run dev` 的 5173 地址访问。
