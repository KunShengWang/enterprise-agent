# Enterprise Agent

一个以“可解释、可恢复、可约束”为核心的 Java 17 / Spring Boot 4.1 Agent 工程项目。项目已经从单一 Runtime 演进为三层结构：

```text
产品控制面：Unified Agent Workbench
业务编排面：OrderCare Case / Incident Command / Recovery Plan
执行面：DefaultAgentRuntime + Capability / Tool Runtime
```

当前稳定事实以已提交代码 `b6207a4` 为基线。文档类型和事实源规则见 [文档索引](docs/documentation-index.md)。

## 核心执行语义

单 Agent Runtime 使用同一条持久化 Model–Tool Loop：

```text
用户请求
→ 输入 Guardrail
→ Session Lease / Run / Timeline / Budget
→ Context 投影，必要时压缩
→ Provider 原生流式模型调用
→ assistantText 或 native tool_calls
→ Capability / Profile / 阶段可见性 / Schema / Tool Policy
→ allow / approval / deny
→ ToolExecutionClaim 与 ToolHandler
→ ToolResult 回填同一时间线
→ 继续模型循环，直到明确终止原因
```

默认模型协议是 `NativeToolCallingAgentModelGateway`。它只负责 Spring AI / Provider 消息和 `tools/tool_calls` 的转换，不执行工具；真正的权限、审批、预算、幂等和副作用控制仍由 Runtime 完成。`AGENT_MODEL_TOOL_CALLING_MODE=json` 只用于兼容旧协议或特定测试。

## Unified Agent Workbench

统一入口不是直接调用单 Agent：

```text
POST /api/agent/conversations/{conversationId}/inputs
→ 输入先落库
→ WorkCommandClassifier：控制现有任务还是开始新目标
→ AgentWorkItem
→ UnifiedTaskRouter + Java RoutePolicyValidator
→ Preview / Explicit Confirmation（危险路由）
→ DispatchCoordinator
→ ExecutionAdapter
→ Run / Incident / Recovery Plan
→ WorkLink + WorkEvent + PublicPresentation
```

四个稳定执行目标：

- `GENERAL_AGENT`
- `ORDERCARE_CASE`
- `INCIDENT_INVESTIGATION`
- `INCIDENT_RECOVERY_PLAN`

Workbench 已覆盖输入幂等、三维 WorkItem 状态、路由与派发对账、跨源事件投影、SSE 断线重放、Primary Run 增量、执行树、统一命令控制、分层预算和多实例 claim/lease/fencing。

## OrderCare 业务闭环

项目的核心业务场景是 FlowOrder 异常订单诊断、事故调查和受控恢复：

```text
业务现象或明确标识
→ Incident Scope Discovery / Case Inspect
→ FlowOrder 权威只读事实
→ Commander 调度 Order / Inventory / MQ Specialist
→ 结构化 Evidence + Conflict
→ Reviewer + Java Assessment Assembler
→ 可选 Recovery Plan
→ 不可变 Proposal
→ 版本绑定人工审批
→ 原 actionRequestId 幂等执行
→ UNKNOWN 对账与确定性收敛
```

### 受控 Multi-Agent

Commander 通过受控 SubAgent Tool 调度 Specialist，而不是让多个模型自由聊天：

- SubAgent Tool 必须只读、低风险、`parallelSafe`、`singleUse`；
- 每个 Specialist 使用独立 Run、Profile、工具白名单和预算；
- Specialist 只提交结构化 Evidence；
- Reviewer 必须引用有效 `evidenceId/conflictId`；
- Java 校验角色覆盖、证据引用、冲突一致性和 Assessment Schema；
- Phase 3 使用 PostgreSQL lease、heartbeat、stale scan 和 fencing token 处理多实例接管。

### Incident Scope Discovery

用户不必知道内部 requestId 或 queueName，可以输入：

```text
调查昨晚订单超时但库存未释放的问题。
只调查并生成事故 Assessment，不执行恢复。
```

Java 会使用 FlowOrder 固定只读接口发现候选范围，生成带版本和 fingerprint 的 Preview，必须经过用户显式确认后才启动调查。模型不能生成或猜测内部标识。

当前自动时间表达是有界白名单：`前天`、`昨晚`、`今天`、`最近/过去 N 小时（1～24）` 和不超过 24 小时的 ISO `start/end`；并非任意自然语言时间解析器。

## 关键工程能力

- Run Checkpoint、Session Lease、协作式暂停、同 Run 恢复和崩溃恢复；
- Capability Registry、ExecutionProfile、Tool Guardrail、HITL 和 ToolExecutionClaim；
- 写响应丢失后的原 Action 对账，区分 `SUBMITTED` 与 `RESOLVED`；
- PostgreSQL Timeline、WorkItem、WorkEvent、Projection Cursor 和幂等状态推进；
- Provider 原生 Tool Calling、正文 `MODEL_DELTA`、心跳、gap/replay 和 Child Run 隔离；
- RAG、pgvector 长期记忆、Trace、Eval、Replay 和 PublicPresentation；
- 事故级 Commander / Specialist / Reviewer / Planner 与结构化证据协议。

## 技术栈

- Java 17
- Spring Boot 4.1.0 / WebFlux
- Spring AI 2.0.0 / DeepSeek
- PostgreSQL + pgvector
- FlowOrder：MySQL、RabbitMQ、Nacos（业务联调时）
- Vue 3 + TypeScript + Vite
- Maven / npm

## 快速启动

最小 Runtime 依赖 PostgreSQL；RAG/Memory 需要 pgvector；OrderCare 与事故场景还需要 FlowOrder 和对应中间件。

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek Key"
$env:EMBEDDING_API_KEY="你的 Embedding Key"
$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="你的数据库密码"

mvn.cmd spring-boot:run
```

完整 Unified Workbench 本地开关见 [构建与运行](docs/build-and-run.md)。后端默认端口为 `8083`，前端开发端口为 `5173`。

```powershell
cd frontend
npm install
npm run dev
```

浏览器访问 `http://127.0.0.1:5173/`。`/` 是唯一普通任务入口；Run 历史、审批、事故和 API Lab 是高级观测入口。

## API 入口

推荐使用统一输入：

```http
POST /api/agent/conversations/{conversationId}/inputs
Idempotency-Key: <clientInputId>
Content-Type: application/json

{"content":"介绍 Spring Boot IoC","metadata":{}}
```

`POST /api/agent/runs` 仍保留为直接学习/调试单 Agent Runtime 的入口，不代表统一产品入口。详见 [API 使用指南](docs/api-guide.md)。

## 文档入口

- [文档索引与事实源规则](docs/documentation-index.md)
- [当前架构](docs/architecture.md)
- [构建与运行](docs/build-and-run.md)
- [API 使用指南](docs/api-guide.md)
- [学习顺序](docs/learning-guide.md)
- [面试讲法](docs/interview-guide.md)
- [Unified Agent Workbench 前端](docs/frontend-learning-console.md)
- [当前边界](docs/remaining-gaps.md)
- [Incident Scope Discovery Evidence](docs/reports/incident-scope-discovery-v1-evidence.md)
- [Unified Workbench M3-D Evidence](docs/reports/unified-agent-workbench-m3-d-evidence.md)
- [Incident Command Phase 3 Evidence](docs/reports/ordercare/incident-command-phase3-evidence.md)

## 重要边界

这是有真实工程深度的学习与面试项目，但不能宣称已经具备完整生产平台能力。当前仍缺少内建生产认证授权、OS/容器级 Sandbox、Flyway/Liquibase 迁移治理、正式密钥轮换、外部告警与完整容量/SLO 证据。详见 [仍然存在的边界](docs/remaining-gaps.md)。
