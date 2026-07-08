# 构建与启动说明

本文档用于保证项目可以被稳定构建、启动和演示。

## 环境要求

- JDK 17
- Maven 3.9+
- PostgreSQL 15+，安装 pgvector 扩展
- Node.js，只有启用 filesystem MCP 时需要
- DeepSeek API Key
- 智谱 Embedding API Key

## 构建验证

```powershell
mvn test
```

当前已验证：

```text
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Maven 缺类问题

如果执行 Maven 时出现：

```text
java.lang.NoClassDefFoundError: org/apache/http/client/HttpResponseException
```

原因不是项目代码，而是本机 Maven 运行时缺少 Apache HttpClient 4.x jar。

本机已采用的修复方式：

```powershell
Copy-Item D:\Maven3.9.9\repo\org\apache\httpcomponents\httpclient\4.5.14\httpclient-4.5.14.jar `
  D:\Maven3.9.9\apache-maven-3.9.12\lib\httpclient-4.5.14.jar

Copy-Item D:\Maven3.9.9\repo\org\apache\httpcomponents\httpcore\4.4.16\httpcore-4.4.16.jar `
  D:\Maven3.9.9\apache-maven-3.9.12\lib\httpcore-4.4.16.jar
```

更稳妥的长期方案是重新下载完整 Maven 发行版，或使用项目级 Maven Wrapper。

## PostgreSQL / pgvector 准备

进入 PostgreSQL 后创建数据库和扩展：

```sql
CREATE DATABASE enterprise_agent;
\c enterprise_agent
CREATE EXTENSION IF NOT EXISTS vector;
```

如果使用 Docker，可以按自己的 PostgreSQL 镜像配置启动，只要最终暴露：

```text
jdbc:postgresql://localhost:5432/enterprise_agent
```

## 环境变量

真实模型：

```powershell
$env:DEEPSEEK_API_KEY="你的 DeepSeek API Key"
$env:DEEPSEEK_CHAT_MODEL="deepseek-chat"
```

Embedding：

```powershell
$env:EMBEDDING_API_KEY="你的智谱 API Key"
$env:EMBEDDING_BASE_URL="https://open.bigmodel.cn/api/paas/v4"
$env:EMBEDDING_MODEL="embedding-3"
$env:EMBEDDING_DIMENSION="1024"
```

RAG 数据库：

```powershell
$env:RAG_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:RAG_POSTGRES_USERNAME="postgres"
$env:RAG_POSTGRES_PASSWORD="1234"
```

Memory 默认复用 RAG 数据库，也可以单独配置：

```powershell
$env:MEMORY_MODE="jdbc"
$env:MEMORY_POSTGRES_URL="jdbc:postgresql://localhost:5432/enterprise_agent"
$env:MEMORY_POSTGRES_USERNAME="postgres"
$env:MEMORY_POSTGRES_PASSWORD="1234"
```

## 启动

```powershell
mvn spring-boot:run
```

启动后验证：

```powershell
curl.exe http://localhost:8080/api/agent/health
```

## RAG 初始化

确认 `data/rag-docs` 下有文档后执行：

```powershell
curl.exe -X POST http://localhost:8080/api/agent/rag/ingest
curl.exe -X POST http://localhost:8080/api/agent/rag/index
curl.exe http://localhost:8080/api/agent/rag/stats
```

## MCP 启用

默认配置里 MCP 是关闭的：

```yaml
enterprise-agent:
  mcp:
    enabled: false
```

启用前确认：

- Node.js 可用
- `MCP_NPX_CLI` 指向本机 `npx-cli.js`
- `data/mcp-sandbox` 目录存在
- 自研 ticket MCP server 已经编译到 `target/classes`

启用：

```yaml
enterprise-agent:
  mcp:
    enabled: true
```

然后先运行：

```powershell
mvn test
mvn spring-boot:run
curl.exe http://localhost:8080/api/agent/tools
```

确认工具列表里出现：

```text
mcp.filesystem.*
mcp.ticket.*
```

## 常见问题

### 1. RAG ingest 失败

重点检查：

- PostgreSQL 是否启动
- `enterprise_agent` 数据库是否存在
- pgvector extension 是否安装
- `RAG_POSTGRES_PASSWORD` 是否和本机一致
- `EMBEDDING_API_KEY` 是否配置

### 2. RAG index 失败

重点检查：

- pgvector 版本是否支持当前索引类型
- 当前用户是否有创建索引权限
- 可以先把 `enterprise-agent.rag.index.type` 改成 `ivfflat` 或 `hnsw` 重新尝试

### 3. Agent 主调用失败

重点检查：

- `DEEPSEEK_API_KEY` 是否配置
- 网络是否能访问 DeepSeek API
- `enterprise-agent.mock-mode` 是否被误改
- Trace 接口是否记录了失败原因

### 4. PowerShell profile 报错

如果命令执行时出现：

```text
profile.ps1 cannot be loaded because running scripts is disabled
```

这是本机 PowerShell 执行策略导致的 profile 加载提示，不影响 Maven、Git 或 curl 命令执行结果。
