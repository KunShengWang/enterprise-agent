package com.agent.platform.runtime;

import java.util.Map;
import java.util.Optional;

/**
 * 为一次模型请求提供当前的、非时间线 canonical business context。
 *
 * <p>Provider 自己决定是否支持给定的执行 Profile；返回的 context 只用于本轮模型投影，
 * 不代表要写入 Agent Timeline 或长期记忆。</p>
 */
public interface AgentCanonicalContextProvider {

    Optional<CanonicalContext> provide(String tenantId,
                                       String userId,
                                       String conversationId,
                                       AgentExecutionProfile profile);

    record CanonicalContext(String contextId,
                            String content,
                            Map<String, Object> metadata) {

        public CanonicalContext {
            if (contextId == null || contextId.isBlank()) {
                throw new IllegalArgumentException("canonical context id must not be blank");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("canonical context content must not be blank");
            }
            contextId = contextId.trim();
            content = content.trim();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
