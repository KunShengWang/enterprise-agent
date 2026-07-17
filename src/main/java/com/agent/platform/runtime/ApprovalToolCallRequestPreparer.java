package com.agent.platform.runtime;

import com.agent.platform.guardrail.ToolPolicyContext;
import com.agent.platform.tool.ToolCallRequest;

/**
 * 在高风险工具进入审批存储前，用服务端事实替换模型提供的业务快照参数。
 */
public interface ApprovalToolCallRequestPreparer {

    boolean supports(String toolName);

    ToolCallRequest prepare(String approvalId,
                            ToolCallRequest request,
                            ToolPolicyContext context);
}
