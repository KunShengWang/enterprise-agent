# API Key 配置说明

## 当前必需

当前默认走真实 ChatModel 和真实 Embedding，不再默认使用模拟 LLM 或模拟向量。

当前需要准备：

```text
DeepSeek API Key
Embedding API Key
PostgreSQL + pgvector
```

项目使用 Spring AI 2.0.0 的 DeepSeek starter，对应配置：

```yaml
spring:
  ai:
    model:
      chat: deepseek
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        model: ${DEEPSEEK_CHAT_MODEL:deepseek-chat}
        temperature: 0.2
        max-tokens: 1200
```

本地启动前，在 PowerShell 中设置：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
$env:DEEPSEEK_CHAT_MODEL="deepseek-chat"
```

如果没有配置 `DEEPSEEK_API_KEY`，项目不应该走 mock 回答；你需要先配置真实 API Key。

## RAG Embedding

V2.0 的 Embedding 调用使用 OpenAI-compatible `/embeddings` 协议。配置：

```powershell
$env:EMBEDDING_API_KEY="你的 Embedding API Key"
$env:EMBEDDING_BASE_URL="https://open.bigmodel.cn/api/paas/v4"
$env:EMBEDDING_MODEL="embedding-3"
$env:EMBEDDING_DIMENSION="1024"
```

如果你使用兼容 OpenAI embedding 协议的其他服务，只要修改 `EMBEDDING_BASE_URL`、`EMBEDDING_MODEL` 和 `EMBEDDING_DIMENSION`。

## RAG PostgreSQL

```powershell
$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="postgres"
```

数据库需要安装 pgvector 扩展。项目会在 ingest/search 时尝试执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

如果数据库用户没有扩展权限，可以先用管理员账号执行 [rag-pgvector-schema.sql](rag-pgvector-schema.sql)。

## 后续可能需要

后续如果做联网搜索工具，可能需要：

```text
Tavily API Key 或其他搜索 API Key
```

后续如果做 MCP，是否需要 API Key 取决于 MCP Server 本身。有些 MCP Server 只是本地文件或本地命令，不需要额外 API Key；有些 MCP Server 会代理第三方服务，就需要对应服务的 Key。
