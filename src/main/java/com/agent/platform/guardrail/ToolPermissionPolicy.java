package com.agent.platform.guardrail;

import com.agent.platform.tool.ToolCallRequest;
import com.agent.platform.tool.ToolDefinition;

public interface ToolPermissionPolicy {

    /**
     * 工具风险检查
     */
    GuardrailDecision check(ToolDefinition toolDefinition, ToolCallRequest toolCallRequest);

    /**
     * 工具权限检查：
     * 1、检查调用的工具是否是租户禁用的工具
     * 2、检查调用的工具是否是管理员才能使用的工具
     * 3、检查的是工具调用参数是否越过系统允许的资源边界，目前主要检查两类高风险参数：文件路径 path、网络地址 url
     * 4、根据配置文件 yaml 的设置判断工具调用是阻塞还是允许还是人工审批
     */
    default GuardrailDecision check(ToolDefinition toolDefinition,
                                    ToolCallRequest toolCallRequest,
                                    ToolPolicyContext context) {
        return check(toolDefinition, toolCallRequest);
    }
}
