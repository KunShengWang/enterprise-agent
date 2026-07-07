# V3.0 Tool Calling / MCP

这一版的目标是让 Agent 从“能回答”升级为“能执行动作”。

核心不是写几个工具方法，而是把工具调用做成 Agent 主链路中的可观测执行阶段：

```text
用户问题
  -> IntentRouter 判断进入 TOOL 分支
  -> ToolRegistry 返回本地工具和 MCP 工具
  -> ToolCallPlanner 让 LLM 生成工具调用计划
  -> JSON Schema 参数校验
  -> Guardrail 判断风险等级
  -> ApprovalService 处理高风险人工确认
  -> ToolExecutor 执行本地工具或 MCP 工具
  -> ToolRunRecorder 记录成功率、耗时、参数、错误
  -> 工具结果进入 PromptAssembler
  -> LLM 基于工具结果生成最终回答
```

## 重点类

先看主链路：

```text
V1AgentExecutor
  -> executeToolBranch()
```

再看工具层：

```text
ToolDefinition            工具定义：name、description、inputSchema、riskLevel
ToolRegistry              工具注册表：统一返回本地工具和 MCP 工具
ToolCallPlanner           工具计划器：让 LLM 选择工具并生成参数
ToolParameterValidator    参数校验：根据 JSON Schema 校验必填、类型、枚举
ToolExecutor              工具执行入口
ToolRunRecorder           工具调用记录和统计
```

本地工单工具：

```text
ticket_status
ticket_create
ticket_priority_update
ticket_close
```

MCP 接入：

```text
McpToolGateway
StdioMcpToolGateway
McpProperties
```

## 本地 Tool Calling

工具不是由 LLM 直接执行。

LLM 只输出工具计划：

```json
{
  "needsTool": true,
  "toolName": "ticket_status",
  "arguments": {
    "ticketId": "T1001"
  },
  "reason": "用户要查询具体工单状态",
  "confidence": 0.8
}
```

Java 后端负责：

```text
1. 检查工具是否存在
2. 校验参数是否符合 inputSchema
3. 执行 Guardrail
4. 必要时发起 Approval
5. 执行工具
6. 记录 ToolRun
7. 把工具结果回填给 LLM
```

## ReAct 简化循环

`V1AgentExecutor` 现在支持最多 `enterprise-agent.max-tool-calls-per-run` 次工具循环：

```text
plan tool
-> execute tool
-> append result
-> plan next tool
-> stop
```

当前版本没有做复杂 Planner Agent，但保留了 ReAct 的核心思想：

```text
模型看到上一次工具结果后，可以决定是否继续调用工具。
```

## MCP 接入方式

MCP 默认关闭：

```yaml
enterprise-agent:
  mcp:
    enabled: false
```

开启后按 stdio 方式接入 MCP Server：

```yaml
enterprise-agent:
  mcp:
    enabled: true
    server-name: filesystem
    tool-name-prefix: mcp.filesystem.
    command: node
    args:
      - D:/NodeJS/24.11/setup/node_modules/npm/bin/npx-cli.js
      - -y
      - "@modelcontextprotocol/server-filesystem"
      - D:/JDK/IDEA/java_reinforcement_learning/enterprise-agent/data/mcp-sandbox
```

MCP 工具会被转换为统一的 `ToolDefinition`：

```text
MCP tools/list
  -> ToolDefinition
  -> ToolRegistry
  -> ToolCallPlanner
  -> ToolExecutor
  -> MCP tools/call
```

所以项目中需要区分：

```text
Tool Calling：Agent 调用工具的执行机制
MCP：外部工具服务的标准协议和工具来源
```

## 调试接口

查看工具列表：

```text
GET /api/agent/tools
```

手动调用工具：

```text
POST /api/agent/tools/call
Content-Type: application/json

{
  "toolName": "ticket_status",
  "requestId": "manual-1",
  "arguments": {
    "ticketId": "T1001"
  }
}
```

查看工具调用记录：

```text
GET /api/agent/tools/runs?limit=20
```

查看工具调用统计：

```text
GET /api/agent/tools/runs/stats
```

## 学习重点

这版你要能讲清楚：

```text
1. 工具定义如何暴露给 LLM
2. LLM 为什么只负责选择工具和生成参数
3. Java 后端为什么必须做参数校验和权限控制
4. 工具结果如何进入 PromptAssembler
5. MCP 工具如何被转换成统一 ToolDefinition
6. 工具调用如何记录成功率、耗时和错误
7. 高风险工具为什么要走 Guardrail + Approval
```

面试表达：

```text
我在 Agent 主链路中实现了可观测工具执行层，支持本地工具和 MCP 工具统一注册，
由 LLM 生成工具调用计划，后端进行 JSON Schema 参数校验、Guardrail 风险控制、
人工确认、执行调度和 ToolRun 统计，工具结果再回填给 LLM 生成最终回答。
```
