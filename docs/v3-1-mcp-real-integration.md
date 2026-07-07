# V3.1 MCP 真实联调和自研 Ticket MCP Server

这一版完成三件事：

```text
1. 接入官方 filesystem MCP Server 并完成真实 stdio 联调
2. 实现自研 ticket-mcp-server
3. filesystem MCP、ticket MCP、本地 Java 工具统一进入 ToolRegistry
```

## 最终结构

```text
本地 Java 工具
  - ticket_status
  - ticket_create
  - ticket_priority_update
  - ticket_close

官方 filesystem MCP
  - mcp.filesystem.read_file
  - mcp.filesystem.list_directory
  - mcp.filesystem.write_file
  - ...

自研 ticket MCP
  - mcp.ticket.ticket_status
  - mcp.ticket.ticket_create
  - mcp.ticket.ticket_priority_update
  - mcp.ticket.ticket_close

        ↓

ToolRegistry
        ↓
ToolCallPlanner
        ↓
ToolExecutor
        ↓
ToolRunRecorder / Trace
```

## filesystem MCP

filesystem MCP 使用官方 npm 包：

```text
@modelcontextprotocol/server-filesystem
```

默认只允许访问：

```text
data/mcp-sandbox
```

这是为了避免 Agent 工具误读真实项目代码、用户目录或敏感文件。

配置位置：

```yaml
enterprise-agent:
  mcp:
    enabled: true
    servers:
      - enabled: true
        server-name: filesystem
        tool-name-prefix: mcp.filesystem.
        protocol-version: "2025-11-25"
        command: node
        args:
          - D:/NodeJS/24.11/setup/node_modules/npm/bin/npx-cli.js
          - -y
          - "@modelcontextprotocol/server-filesystem"
          - D:/JDK/IDEA/java_reinforcement_learning/enterprise-agent/data/mcp-sandbox
```

为什么不用 `npx.cmd`：

```text
PowerShell 默认执行策略可能拦截 npx.ps1；
Node 子进程直接 spawn npx.cmd 时，stdio 透传在 Windows 下不稳定；
当前验证通过的方式是 node.exe 直接启动 npx-cli.js。
```

## 自研 ticket-mcp-server

入口类：

```text
com.agent.platform.mcp.server.TicketMcpServerApplication
```

它是独立 stdio JSON-RPC 进程，不依赖 Spring 容器。

支持 MCP 方法：

```text
initialize
notifications/initialized
tools/list
tools/call
```

提供工具：

```text
ticket_status
ticket_create
ticket_priority_update
ticket_close
```

在 Agent 中会被统一映射成：

```text
mcp.ticket.ticket_status
mcp.ticket.ticket_create
mcp.ticket.ticket_priority_update
mcp.ticket.ticket_close
```

配置：

```yaml
enterprise-agent:
  mcp:
    enabled: true
    servers:
      - enabled: true
        server-name: ticket
        tool-name-prefix: mcp.ticket.
        protocol-version: "2025-11-25"
        command: java
        args:
          - -cp
          - target/classes
          - com.agent.platform.mcp.server.TicketMcpServerApplication
```

## Agent 中怎么使用

开启 MCP 后：

```text
GET /api/agent/tools
```

会同时看到：

```text
ticket_status
ticket_create
ticket_priority_update
ticket_close
mcp.filesystem.read_file
mcp.filesystem.list_directory
...
mcp.ticket.ticket_status
mcp.ticket.ticket_create
...
```

手动调用 filesystem MCP：

```text
POST /api/agent/tools/call
Content-Type: application/json

{
  "toolName": "mcp.filesystem.read_file",
  "requestId": "fs-1",
  "arguments": {
    "path": "D:/JDK/IDEA/java_reinforcement_learning/enterprise-agent/data/mcp-sandbox/sample-ticket-note.txt"
  }
}
```

手动调用自研 ticket MCP：

```text
POST /api/agent/tools/call
Content-Type: application/json

{
  "toolName": "mcp.ticket.ticket_status",
  "requestId": "ticket-1",
  "arguments": {
    "ticketId": "T3001"
  }
}
```

## 已验证结果

filesystem MCP：

```text
initialize 成功
tools/list 成功
tools/call read_file 成功
读取 data/mcp-sandbox/sample-ticket-note.txt 成功
```

自研 ticket MCP：

```text
initialize 成功
tools/list 成功
tools/call ticket_status 成功
返回 MCP 工单 T3001 状态
```

Java `StdioMcpToolGateway` 双 Server 联调：

```text
发现 mcp.filesystem.* 工具
发现 mcp.ticket.* 工具
调用 mcp.filesystem.read_file 成功
调用 mcp.ticket.ticket_status 成功
```

## 学习重点

你要重点理解：

```text
MCP Server 本质是独立工具进程
MCP Client 通过 stdio JSON-RPC 调 initialize / tools/list / tools/call
MCP 工具不会直接暴露给 LLM，而是先转成项目统一 ToolDefinition
本地工具和 MCP 工具最后都走同一个 ToolRegistry / ToolExecutor
```

面试表达：

```text
我把 MCP 当成外部工具来源接入，而不是把 MCP 和 Tool Calling 混为一谈。
系统启动后通过 MCP Client 调 tools/list 发现外部工具，并统一转换为 ToolDefinition；
模型只看到统一工具定义，选择工具后由 Java 后端根据 provider 分发到本地执行器或 MCP tools/call。
```
