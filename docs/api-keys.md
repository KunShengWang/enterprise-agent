# API Key 配置说明

## 当前必需

V1.2 默认走真实模型，不再默认使用模拟 LLM。

当前需要申请：

```text
DeepSeek API Key
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

## 后续可能需要

V2 做真实 RAG 时，需要再补：

```text
Embedding 模型 API Key
PostgreSQL + pgvector 或其他向量数据库
```

后续如果做联网搜索工具，可能需要：

```text
Tavily API Key 或其他搜索 API Key
```

后续如果做 MCP，是否需要 API Key 取决于 MCP Server 本身。有些 MCP Server 只是本地文件或本地命令，不需要额外 API Key；有些 MCP Server 会代理第三方服务，就需要对应服务的 Key。
