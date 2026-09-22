# 构建、运行与测试

当前产品为采购 Agent。运行不需要 FlowOrder HTTP、RabbitMQ Management、旧事故调度器或旧领域 SQL。

## 离线验证

JDK 17、Maven，以及前端 Node.js/npm。首次下载依赖需联网；依赖已缓存后：

```powershell
mvn.cmd -o clean test
Push-Location frontend
npm.cmd run build
npm.cmd test
Pop-Location
```

这些命令不等于真实模型、外部 MCP 或 PostgreSQL IT 验收。不要设置 Live Eval 或 IT 环境开关来运行普通离线测试。

## 本地应用

实际持久化使用 PostgreSQL；RAG/Memory 向量功能还需要 pgvector 与 Embedding 配置。当前代码按需初始化公共表，不是生产数据库迁移方案。使用专门开发库，显式填写以下配置，勿照抄默认库执行测试：

```powershell
$env:AGENT_STORAGE_POSTGRES_URL="jdbc:postgresql://<dev-host>:5432/<dev-db>"
$env:AGENT_STORAGE_POSTGRES_USERNAME="<dev-user>"
$env:AGENT_STORAGE_POSTGRES_PASSWORD="<secret>"
$env:RAG_POSTGRES_URL=$env:AGENT_STORAGE_POSTGRES_URL
$env:RAG_POSTGRES_USERNAME=$env:AGENT_STORAGE_POSTGRES_USERNAME
$env:RAG_POSTGRES_PASSWORD=$env:AGENT_STORAGE_POSTGRES_PASSWORD
$env:MEMORY_POSTGRES_URL=$env:AGENT_STORAGE_POSTGRES_URL
$env:MEMORY_POSTGRES_USERNAME=$env:AGENT_STORAGE_POSTGRES_USERNAME
$env:MEMORY_POSTGRES_PASSWORD=$env:AGENT_STORAGE_POSTGRES_PASSWORD
$env:DEEPSEEK_API_KEY="<secret>"
$env:EMBEDDING_API_KEY="<secret>"
$env:WORKBENCH_WEB_ENABLED="true"
$env:WORKBENCH_ROUTING_ENABLED="true"
$env:WORKBENCH_DISPATCH_ENABLED="true"
$env:WORKBENCH_PROJECTION_ENABLED="true"
$env:WORKBENCH_LOCAL_ROLES="USER"
mvn.cmd spring-boot:run
```

默认服务端口 8083。前端在 frontend 执行 `npm.cmd run dev`。本地 Principal 仅供开发，生产入口需要可信认证/授权；不得把本地身份当生产租户隔离方案。

采购默认 synthetic Provider。只读 MCP Provider 的配置与工具前缀见 [采购指南](procurement-sourcing.md)，不要把 MCP Provider 与 RFQ 模拟网关混为一谈。

## RAG 导入

默认 RAG_DOCUMENT_DIR 为 data/rag-docs，递归扫描。不要将目录扩大为仓库根目录、docs 或历史归档。导入会调用 Embedding 并写 pgvector，非离线只读操作。通用 incident-response.md 仍用于 RAG Eval；旧业务 SOP 已退出默认目录。移动文件不会删除旧向量，见 [source 退役边界](retirement-and-history.md)。

## 测试与部署门禁

`tools/workbench-m3-d-evidence.ps1` 的 DeterministicEval、BusinessE2E、Full、All 是离线入口；RoutingModel 需额外显式 Live 开关。FaultRecovery 必须显式提供隔离测试库参数并确认隔离，不接受默认库回退。

旧 docs/sql OrderCare 脚本不属于新环境初始化。历史 SQL 位于只读文本归档，禁止作为迁移脚本执行。现存表、旧任务与向量不在本轮清理范围内。

生产部署前仍需验证认证、秘密管理、真实供应商幂等/对账、数据库迁移与隔离 PostgreSQL IT。RFQ 当前为模拟适配器；不宣称已打通真实采购订单。
