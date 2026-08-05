package com.agent.platform.llm;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 保留 Spring AI 原生结构化响应的模型调用边界。
 *
 * <p>普通 {@link LlmService} 面向只需要文本的业务调用；Agent Runtime 的原生
 * Tool Calling 必须读取 AssistantMessage.toolCalls，因此不能在这一层提前降维为 String。</p>
 */
public interface NativeChatModelClient {

    ChatResponse completeNative(Prompt prompt);

    Flux<ChatResponse> streamNative(Prompt prompt);
}
