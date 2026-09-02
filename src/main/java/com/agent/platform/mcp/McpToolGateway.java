package com.agent.platform.mcp;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolCallResult;
import com.agent.platform.tool.ToolDefinition;

import java.util.List;

public interface McpToolGateway {

    /**
     * 寻找服务商的工具
     */
    List<ToolDefinition> discoverTools();

    /**
     * 显式刷新已经建立的 MCP 会话；不支持独立刷新的兼容实现安全地跳过刷新。
     */
    default List<ToolDefinition> refreshTools() {
        return List.of();
    }

    ToolCallResult callTool(ToolCallRequest request);

    /**
     * 使用已经解析出的 ToolDefinition 执行，避免执行阶段重新猜测 MCP Server。
     */
    default ToolCallResult callTool(ToolDefinition definition, ToolCallRequest request) {
        return callTool(request);
    }
}
