# V4.4 Skills

这一版把 Skills 从静态 if 判断升级为可注册、可检索、可绑定工具的能力层。

## Skill 是什么

Skill 是对某类任务的能力描述。它不是工具本身，而是告诉 Agent：

- 这个 Skill 适合什么任务。
- 应该使用什么 Prompt 策略。
- 可以绑定哪些工具。
- 输入输出 Schema 是什么。
- 风险等级是什么。

## 核心类

- `SkillDefinition`
  - Skill 描述。
  - 包含 name、description、promptTemplate、toolNames、inputSchema、outputSchema、riskLevel。

- `SkillRegistry`
  - Skill 注册表。
  - 支持 list、find、save、delete、search。

- `InMemorySkillRegistry`
  - 内存版 Skill 注册表。
  - 默认内置：
    - `ticket-handling`
    - `knowledge-base-qa`
    - `incident-troubleshooting`

- `SkillSelector`
  - Agent 主流程使用的 Skill 选择接口。

- `StaticSkillSelector`
  - 现在不再硬编码 if，而是调用 `SkillRegistry.search()` 选择最匹配的 Skill。

## API

查看 Skills：

```http
GET /api/agent/skills
```

搜索 Skill：

```http
GET /api/agent/skills/search?query=查询工单状态&limit=5
```

查看单个 Skill：

```http
GET /api/agent/skills/ticket-handling
```

注册或更新 Skill：

```http
POST /api/agent/skills
Content-Type: application/json

{
  "name": "database-troubleshooting",
  "description": "处理数据库慢查询、连接池耗尽、锁等待等问题",
  "promptTemplate": "先澄清故障现象，再要求提供慢 SQL、错误日志和时间窗口。",
  "toolNames": ["ticket_status"],
  "inputSchema": "{}",
  "outputSchema": "{}",
  "riskLevel": "MEDIUM"
}
```

删除 Skill：

```http
DELETE /api/agent/skills/database-troubleshooting
```

## 面试解释

Skills 的价值是把 Agent 能力从代码 if 判断变成可配置的能力描述。

它和 Tool 的区别：

- Tool 是可执行动作。
- Skill 是任务能力说明。
- Skill 可以绑定多个 Tool。
- Agent 先根据任务选择 Skill，再根据 Skill 和用户问题决定是否调用工具、是否走 RAG、如何组织 Prompt。

当前版本是内存注册表，后续可以扩展为文件加载、数据库持久化、向量检索 Skill 描述。
