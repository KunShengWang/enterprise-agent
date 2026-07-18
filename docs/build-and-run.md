# 构建与运行

## 环境

- JDK 17
- Maven 3.9+
- PostgreSQL 14+
- pgvector
- DeepSeek API Key
- 兼容 OpenAI Embedding API 的服务（默认配置为智谱 Embedding）

## 数据库

```sql
CREATE DATABASE enterprise_agent;
\c enterprise_agent
CREATE EXTENSION IF NOT EXISTS vector;
```

应用在首次访问对应能力时按需创建表。建议学习环境使用独立数据库和专用账号；生产环境应改为 Flyway/Liquibase 管理 DDL，而不是授予应用账号扩展管理权限。

## 环境变量

```powershell
$env:DEEPSEEK_API_KEY="..."
$env:EMBEDDING_API_KEY="..."

$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="..."

# 可选；未设置时复用 RAG_POSTGRES_*
$env:MEMORY_POSTGRES_URL=$env:RAG_POSTGRES_URL
$env:MEMORY_POSTGRES_USERNAME=$env:RAG_POSTGRES_USERNAME
$env:MEMORY_POSTGRES_PASSWORD=$env:RAG_POSTGRES_PASSWORD
$env:AGENT_STORAGE_POSTGRES_URL=$env:RAG_POSTGRES_URL
$env:AGENT_STORAGE_POSTGRES_USERNAME=$env:RAG_POSTGRES_USERNAME
$env:AGENT_STORAGE_POSTGRES_PASSWORD=$env:RAG_POSTGRES_PASSWORD

# 可选：只有配置价格和正数成本上限时，成本硬预算才启用
$env:LLM_INPUT_COST_PER_MILLION_TOKENS="..."
$env:LLM_OUTPUT_COST_PER_MILLION_TOKENS="..."
$env:LLM_CACHE_READ_COST_PER_MILLION_TOKENS="..."
$env:LLM_CACHE_WRITE_COST_PER_MILLION_TOKENS="..."
$env:AGENT_MAX_ESTIMATED_COST_PER_RUN="..."
```

源码不包含数据库密码或 API Key 默认值。

## 构建

```powershell
mvn clean test
mvn clean package
```

本次重构删除了大量旧 Bean，因此从旧工作区升级后必须使用 `clean`。普通增量编译不会自动清理已删除的 `.class`。

## 启动

```powershell
mvn clean spring-boot:run
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:8083/api/agent/health
Invoke-RestMethod http://localhost:8083/actuator/health
```

## Mock 模式

```powershell
$env:ENTERPRISE_AGENT_MOCK_MODE="true"
$env:DEEPSEEK_API_KEY="test-key"
$env:EMBEDDING_API_KEY="test-key"
mvn clean spring-boot:run
```

Mock 只替代 ChatModel，不替代 PostgreSQL。因为 Session、Run、Timeline、Policy Audit 和其他运行状态都必须持久化，数据库仍是必需依赖。

Mock 返回普通文本，`JsonAgentModelGateway` 会把它作为最终回答兼容处理；它不适合验证工具循环或长期记忆提取。

## RAG 初始化

将 UTF-8 文本放入 `data/rag-docs`，支持 Markdown、纯文本、JSON/YAML、CSV、日志、SQL 和常见代码文件；单文件最大 5MB。PDF、DOCX 等二进制格式尚未配置专用解析器。

```powershell
Invoke-RestMethod -Method Post http://localhost:8083/api/agent/rag/ingest
Invoke-RestMethod -Method Post http://localhost:8083/api/agent/rag/index
```

## 旧数据库数据

旧版 `agent_run_state.record_json` 使用固定 Workflow 字段，新版改为 `AgentRunPhase`。学习环境建议在首次使用新版前备份后清理旧运行记录；知识库和工单数据可保留。

至少应清理相互关联的旧运行态表数据，具体表名以 `JdbcAgentRuntimeStore`、`JdbcAgentTimelineStore` 和 `JdbcAgentRunControlStore` 的 DDL 为准。不要在不确认环境的情况下直接执行生产删除。
