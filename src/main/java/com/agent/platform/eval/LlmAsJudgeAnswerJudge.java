package com.agent.platform.eval;

import com.agent.platform.agent.AgentResponse;
import com.agent.platform.config.AgentProperties;
import com.agent.platform.llm.LlmService;
import com.agent.platform.prompt.PromptRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Primary
@Component
public class LlmAsJudgeAnswerJudge implements AnswerJudge {

    private static final Pattern SCORE_PATTERN = Pattern.compile("score\\s*[:=]\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE);

    private final LlmService llmService;

    private final HeuristicAnswerJudge fallbackJudge;

    private final AgentProperties agentProperties;

    public LlmAsJudgeAnswerJudge(LlmService llmService,
                                 HeuristicAnswerJudge fallbackJudge,
                                 AgentProperties agentProperties) {
        this.llmService = llmService;
        this.fallbackJudge = fallbackJudge;
        this.agentProperties = agentProperties;
    }

    @Override
    public AnswerJudgement judge(EvalCase evalCase, AgentResponse response) {
        AnswerJudgement deterministic = fallbackJudge.judge(evalCase, response);
        if (agentProperties.isMockMode()) {
            return deterministic;
        }
        try {
            String judgeText = llmService.complete(new PromptRequest(
                    "你是 Agent 自动评测裁判。请根据用例要求判断回答是否正确、是否基于可用证据、是否有编造。只输出 score、grounded、reason 三项。",
                    buildUserPrompt(evalCase, response),
                    List.of(),
                    Map.of("evalCaseId", evalCase.id())
            ));
            double score = parseScore(judgeText);
            boolean modelGrounded = parseGrounded(judgeText, score);
            // 工具/RAG 证据是否存在由结构化 Trace 判定，避免裁判模型把已存在的证据误读为缺失。
            // LLM 裁判继续负责语义正确性评分，但不覆盖确定性的 groundedness 事实。
            return new AnswerJudgement(
                    score,
                    deterministic.grounded(),
                    "llm-as-judge: " + compact(judgeText)
                            + "; deterministicGrounded=" + deterministic.grounded()
                            + "; modelGrounded=" + modelGrounded
            );
        }
        catch (RuntimeException exception) {
            return new AnswerJudgement(
                    deterministic.score(),
                    deterministic.grounded(),
                    deterministic.reason() + "; llm-as-judge fallback because " + exception.getClass().getSimpleName()
            );
        }
    }

    private String buildUserPrompt(EvalCase evalCase, AgentResponse response) {
        String answer = response == null ? "" : response.answer();
        String steps = response == null ? "[]" : response.steps().toString();
        return """
                评测用例：
                id: %s
                question: %s
                expectedKeywords: %s
                expectedTools: %s
                expectRag: %s
                expectToolCall: %s
                forbiddenKeywords: %s

                Agent 回答：
                %s

                Agent 步骤：
                %s

                输出格式：
                score: 0.0-1.0
                grounded: true/false
                reason: 简短原因
                """.formatted(
                evalCase.id(),
                evalCase.question(),
                evalCase.expectedKeywords(),
                evalCase.expectedTools(),
                evalCase.expectRag(),
                evalCase.expectToolCall(),
                evalCase.forbiddenKeywords(),
                answer,
                steps
        );
    }

    private double parseScore(String text) {
        Matcher matcher = SCORE_PATTERN.matcher(text == null ? "" : text);
        if (!matcher.find()) {
            return 0.5;
        }
        double score = Double.parseDouble(matcher.group(1));
        return Math.max(0, Math.min(1, score));
    }

    private boolean parseGrounded(String text, double score) {
        String value = text == null ? "" : text.toLowerCase();
        if (value.contains("grounded: true") || value.contains("grounded=true")) {
            return true;
        }
        if (value.contains("grounded: false") || value.contains("grounded=false")) {
            return false;
        }
        return score >= 0.7;
    }

    private String compact(String text) {
        if (text == null) {
            return "";
        }
        String compacted = text.replaceAll("\\s+", " ").trim();
        return compacted.length() <= 300 ? compacted : compacted.substring(0, 300);
    }
}
