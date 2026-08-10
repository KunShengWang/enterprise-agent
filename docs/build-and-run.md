# 构建与运行

> 实现基线：`b6207a4`。

## 1. 环境分层

| 场景 | 必需依赖 |
|---|---|
| 单 Agent Runtime | JDK 17、Maven、PostgreSQL、DeepSeek Key |
| RAG / Memory | 上述依赖 + pgvector + Embedding Key |
| Unified Workbench | Runtime 依赖 + Workbench 四个 Feature Flag |
| OrderCare Case | Workbench + FlowOrder resource-service |
| Incident / Scope Discovery | Workbench + FlowOrder order/resource-service + MySQL + RabbitMQ；完整本地链路通常还使用 Nacos |

前端需要 Node.js 和 npm。

## 2. PostgreSQL

```sql
CREATE DATABASE enterprise_agent;
\c enterprise_agent
CREATE EXTENSION IF NOT EXISTS vector;
```

当前代码为学习环境按需建表。正式部署仍应改用 Flyway/Liquibase；不要把应用自建表描述为生产迁移方案。

## 3. 最小 Runtime 启动

PowerShell 中逐条设置环境变量，不要把 `A=x;B=y` 粘贴到 IDEA 的 `spring.profiles.active`：

```powershell
$env:DEEPSEEK_API_KEY="..."
$env:EMBEDDING_API_KEY="..."

$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="..."

# 可选；未设置时复用 RAG_POSTGRES_*
$env:AGENT_STORAGE_POSTGRES_URL=$env:RAG_POSTGRES_URL
$env:AGENT_STORAGE_POSTGRES_USERNAME=$env:RAG_POSTGRES_USERNAME
$env:AGENT_STORAGE_POSTGRES_PASSWORD=$env:RAG_POSTGRES_PASSWORD

mvn.cmd spring-boot:run
```

默认端口：`8083`。

```powershell
Invoke-RestMethod http://localhost:8083/api/agent/health
Invoke-RestMethod http://localhost:8083/actuator/health
```

## 4. 模型 Tool Calling 模式

默认：

```powershell
$env:AGENT_MODEL_TOOL_CALLING_MODE="native"
```

使用 `NativeToolCallingAgentModelGateway` 和 Provider 原生 `tools/tool_calls`。兼容模式：

```powershell
$env:AGENT_MODEL_TOOL_CALLING_MODE="json"
```

`json` 只用于兼容和特定诊断，不应继续写成默认架构。

## 5. Unified Workbench 启动

在最小 Runtime 环境变量基础上增加：

```powershell
$env:WORKBENCH_WEB_ENABLED="true"
$env:WORKBENCH_ROUTING_ENABLED="true"
$env:WORKBENCH_DISPATCH_ENABLED="true"
$env:WORKBENCH_PROJECTION_ENABLED="true"
$env:WORKBENCH_INSTANCE_ID="agent-local-01"

# 本地演示身份，不是生产认证
$env:WORKBENCH_LOCAL_TENANT_ID="local-tenant"
$env:WORKBENCH_LOCAL_PRINCIPAL_ID="local-user"
$env:WORKBENCH_LOCAL_ROLES="USER,INCIDENT_OPERATOR"

mvn.cmd spring-boot:run
```

如果只设置 PostgreSQL 变量而没有开启上述 Feature Flag，统一 Workbench API 可能不注册，前端访问 `/api/agent/conversations/.../work-items` 会得到 404。

## 6. OrderCare / Incident / Scope Discovery

enterprise-agent：

```powershell
$env:ORDERCARE_INCIDENT_COMMAND_ENABLED="true"
$env:ORDERCARE_INCIDENT_SUB_AGENT_TOOLS_ENABLED="true"
$env:ORDERCARE_INCIDENT_RECOVERY_PLANNER_ENABLED="true"
$env:ORDERCARE_INCIDENT_PHASE3_ENABLED="true"
$env:ORDERCARE_INCIDENT_EXECUTION_KILL_SWITCH="false"
$env:ORDERCARE_INCIDENT_INSTANCE_ID="agent-local-01"

$env:FLOWORDER_BASE_URL="http://localhost:8081"
$env:FLOWORDER_ORDER_BASE_URL="http://localhost:8082"
$env:FLOWORDER_INCIDENT_SCOPE_INTERNAL_TOKEN="local-incident-scope-token"

$env:RABBITMQ_MANAGEMENT_BASE_URL="http://localhost:15672/api"
$env:RABBITMQ_MANAGEMENT_USERNAME="guest"
$env:RABBITMQ_MANAGEMENT_PASSWORD="guest"
```

FlowOrder 必须配置相同的 Scope Discovery 内部 token，并开启对应内部只读接口。环境变量要放在 IDEA Run Configuration 的 **Environment variables**，不要放在 **Active profiles**：

```text
FLOWORDER_ADMIN_ENABLED=true
FLOWORDER_INCIDENT_SCOPE_INTERNAL_TOKEN=local-incident-scope-token
```

IDEA 的环境变量编辑器可以用分号分隔多个键值；PowerShell 中应使用两个独立的 `$env:` 赋值。

## 7. 前端

```powershell
cd frontend
npm install
npm run dev
```

- 开发地址：`http://127.0.0.1:5173/`
- Vite Preview：`http://127.0.0.1:4173/`
- `/api` 默认代理：`http://localhost:8083`

前端门禁：

```powershell
cd frontend
npm test
npx.cmd vue-tsc -b
npm run build
```

## 8. Mock 模式

```powershell
$env:ENTERPRISE_AGENT_MOCK_MODE="true"
$env:DEEPSEEK_API_KEY="test-key"
$env:EMBEDDING_API_KEY="test-key"
mvn.cmd test
```

Mock 不替代 PostgreSQL，也不能证明真实模型 ToolCall、Scope Discovery 跨服务链路或 RabbitMQ 恢复正确性。

## 9. RAG 初始化

将 UTF-8 文本放入 `data/rag-docs`：

```powershell
Invoke-RestMethod -Method Post http://localhost:8083/api/agent/rag/ingest
Invoke-RestMethod -Method Post http://localhost:8083/api/agent/rag/index
```

当前未提供 PDF/DOCX/PPTX 的专用解析链路。

## 10. 构建与验证

```powershell
mvn.cmd clean test
mvn.cmd clean package
```

从旧代码切换后建议先 `mvn clean`，避免 `target/classes` 保留已删除 Bean。

文档或局部代码修改至少执行：

```powershell
git diff --check
```

涉及 Workbench/Incident 的真实 PostgreSQL、FlowOrder HTTP、MySQL 和 RabbitMQ 测试是 opt-in 环境门禁；默认 `mvn test` 中跳过的外部测试不能表述为本次已执行。

## 11. 常见问题

### Workbench API 404

检查：

```text
WORKBENCH_WEB_ENABLED=true
WORKBENCH_ROUTING_ENABLED=true
WORKBENCH_DISPATCH_ENABLED=true
WORKBENCH_PROJECTION_ENABLED=true
```

并确认 8083 是当前代码启动的进程，而不是旧实例。

### Scope Discovery 失败

检查：

- enterprise-agent 与 FlowOrder token 是否完全一致；
- FlowOrder 8081/8082 是否可达；
- MySQL fixture 是否位于请求时间范围内；
- RabbitMQ Management 15672 是否可达；
- 输入时间是否属于受支持白名单。

### SSE `ECONNRESET`

先查看 WorkItem/Run 是否已在后端失败或后端是否重启。Vite proxy 的 `ECONNRESET` 只是连接被服务端关闭的现象，不是业务根因；应结合 WorkEvent、Run Event、correlationId 和后端日志定位。
