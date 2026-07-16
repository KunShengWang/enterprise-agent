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

    ToolCallResult callTool(ToolCallRequest request);
}
