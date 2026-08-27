package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.llm.LlmService;
import com.agent.platform.llm.NativeChatModelClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 在模型协议边界选择原生 Tool Calling 或旧版 JSON 兼容协议。
 */
@Configuration
public class AgentModelGatewayConfiguration {

    @Bean
    public AgentModelGateway agentModelGateway(AgentProperties properties,
                                               LlmService llmService,
                                               ObjectMapper objectMapper,
                                               ObjectProvider<NativeChatModelClient> nativeClientProvider,
                                               DeepSeekChatProperties deepSeekChatProperties) {
        if (properties.isMockMode()
                || properties.getModelToolCallingMode() == AgentProperties.ModelToolCallingMode.JSON) {
            return new JsonAgentModelGateway(llmService, objectMapper);
        }
        NativeChatModelClient nativeClient = nativeClientProvider.getIfAvailable();
        if (nativeClient == null) {
            throw new IllegalStateException(
                    "native Tool Calling requires a NativeChatModelClient; "
                            + "set enterprise-agent.model-tool-calling-mode=json only for explicit compatibility mode"
            );
        }
        return new NativeToolCallingAgentModelGateway(
                nativeClient, objectMapper, deepSeekChatProperties.toOptions());
    }
}
