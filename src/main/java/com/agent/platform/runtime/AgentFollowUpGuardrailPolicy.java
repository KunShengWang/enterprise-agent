package com.agent.platform.runtime;

import com.agent.platform.guardrail.GuardrailDecision;

import java.util.Optional;

/**
 * 对服务端生成的结构化 Follow-up 做确定性安全判定。
 *
 * <p>策略只能基于持久化 Run、结构化输入和权威业务状态放行；无法确认来源时返回 empty，
 * Runtime 会继续使用通用输入 Guardrail。</p>
 */
public interface AgentFollowUpGuardrailPolicy {

    Optional<GuardrailDecision> evaluate(AgentRunRecord storedRun, AgentFollowUpInput input);
}
