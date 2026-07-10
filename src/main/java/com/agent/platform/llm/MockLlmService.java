package com.agent.platform.llm;

import com.agent.platform.prompt.PromptRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "enterprise-agent", name = "mock-mode", havingValue = "true")
public class MockLlmService implements LlmService {

    @Override
    public String complete(PromptRequest promptRequest) {
        String context = String.join("\n", promptRequest.contextBlocks());
        String question = promptRequest.userPrompt() == null ? "" : promptRequest.userPrompt();
        if (context.contains("Tool[")) {
            return "根据工具返回结果：" + extractToolSummary(context);
        }
        if (context.contains("RAG[")) {
            return "根据知识库资料：" + extractRagSummary(context);
        }
        if (question.contains("能做什么") || question.contains("你是谁") || question.toLowerCase().contains("hello")) {
            return "我是企业知识库与智能工单 Agent，可以回答知识库问题，也可以查询和处理工单。";
        }
        return "当前资料不足，无法给出可靠答案。请补充更明确的问题或指定工单编号。";
    }

    @Override
    public Flux<String> stream(PromptRequest promptRequest) {
        String answer = complete(promptRequest);
        return Flux.fromArray(answer.split("(?<=[。！？])"))
                .delayElements(Duration.ofMillis(30));
    }

    private String extractToolSummary(String context) {
        return context.lines()
                .filter(line -> line.startsWith("Tool["))
                .map(line -> line.substring(line.indexOf(":") + 1).trim())
                .collect(Collectors.joining("；"));
    }

    private String extractRagSummary(String context) {
        return context.lines()
                .filter(line -> line.startsWith("RAG["))
                .limit(2)
                .map(line -> line.substring(line.indexOf("]") + 1).trim())
                .collect(Collectors.joining("；"));
    }
}
