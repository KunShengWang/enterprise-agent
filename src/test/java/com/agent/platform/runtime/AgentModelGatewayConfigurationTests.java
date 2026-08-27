package com.agent.platform.runtime;

import com.agent.platform.config.AgentProperties;
import com.agent.platform.llm.LlmService;
import com.agent.platform.llm.NativeChatModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatProperties;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentModelGatewayConfigurationTests {

    @Test
    void selectsNativeGatewayByDefault() {
        AgentProperties properties = new AgentProperties();
        NativeChatModelClient nativeClient = mock(NativeChatModelClient.class);
        ObjectProvider<NativeChatModelClient> provider = provider(nativeClient);

        AgentModelGateway gateway = new AgentModelGatewayConfiguration().agentModelGateway(
                properties, mock(LlmService.class), new ObjectMapper(), provider, deepSeekProperties());

        assertInstanceOf(NativeToolCallingAgentModelGateway.class, gateway);
    }

    @Test
    void selectsJsonGatewayForExplicitCompatibilityMode() {
        AgentProperties properties = new AgentProperties();
        properties.setModelToolCallingMode(AgentProperties.ModelToolCallingMode.JSON);

        AgentModelGateway gateway = new AgentModelGatewayConfiguration().agentModelGateway(
                properties, mock(LlmService.class), new ObjectMapper(), provider(null), deepSeekProperties());

        assertInstanceOf(JsonAgentModelGateway.class, gateway);
    }

    @Test
    void keepsMockModeOnTextProtocol() {
        AgentProperties properties = new AgentProperties();
        properties.setMockMode(true);

        AgentModelGateway gateway = new AgentModelGatewayConfiguration().agentModelGateway(
                properties, mock(LlmService.class), new ObjectMapper(), provider(null), deepSeekProperties());

        assertInstanceOf(JsonAgentModelGateway.class, gateway);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<NativeChatModelClient> provider(NativeChatModelClient client) {
        ObjectProvider<NativeChatModelClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private DeepSeekChatProperties deepSeekProperties() {
        DeepSeekChatProperties properties = new DeepSeekChatProperties();
        properties.setModel("deepseek-chat");
        properties.setTemperature(0.2);
        properties.setMaxTokens(4096);
        return properties;
    }
}
