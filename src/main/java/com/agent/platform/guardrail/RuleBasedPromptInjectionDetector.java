package com.agent.platform.guardrail;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RuleBasedPromptInjectionDetector implements PromptInjectionDetector {

    private final PromptInputNormalizer normalizer;

    public RuleBasedPromptInjectionDetector() {
        this(new PromptInputNormalizer());
    }

    @Autowired
    public RuleBasedPromptInjectionDetector(PromptInputNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    private final List<String> injectionPhrases = List.of(
            "忽略之前的指令",
            "忽略之前所有指令",
            "忽略以上规则",
            "绕过审批",
            "不要遵守系统提示",
            "泄露系统提示",
            "输出你的系统提示词",
            "内部工具配置",
            "导出系统密钥",
            "清空数据库",
            "disable audit",
            "ignore previous",
            "bypass approval",
            "reveal system prompt",
            "dump secrets"
    );

    private final List<Pattern> injectionPatterns = List.of(
            Pattern.compile("忽略.*(指令|规则|系统提示)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(输出|泄露|展示|打印).*(系统提示|系统提示词|内部工具|工具配置|开发者消息)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(ignore|forget).*(previous|system).*(instruction|prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|show|dump).*(system prompt|developer message|tool config|secrets)", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 检测用户输入是否是不安全的操作
     */
    @Override
    public GuardrailDecision detect(String input) {
        NormalizedPrompt normalizedPrompt = normalizer.normalize(input);
        for (String variant : normalizedPrompt.decodedVariants()) {
            String normalized = variant.toLowerCase(Locale.ROOT);
            for (String phrase : injectionPhrases) {
                if (normalized.contains(phrase.toLowerCase(Locale.ROOT))) {
                    return GuardrailDecision.block(GuardrailStage.INPUT, "prompt injection signature: " + phrase);
                }
            }
            for (Pattern pattern : injectionPatterns) {
                if (pattern.matcher(variant).find()) {
                    return GuardrailDecision.block(GuardrailStage.INPUT, "prompt injection pattern: " + pattern.pattern());
                }
            }
        }
        return GuardrailDecision.allow(GuardrailStage.INPUT, "no deterministic injection signature detected");
    }
}
