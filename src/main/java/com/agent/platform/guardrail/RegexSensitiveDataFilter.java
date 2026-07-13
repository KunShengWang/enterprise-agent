package com.agent.platform.guardrail;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class RegexSensitiveDataFilter implements SensitiveDataFilter {

    private final Pattern apiKeyPattern = Pattern.compile("sk-[a-zA-Z0-9]{8,}");

    private final Pattern phonePattern = Pattern.compile("1[3-9]\\d{9}");

    private final Pattern idCardPattern = Pattern.compile("\\d{17}[0-9Xx]");

    private final Pattern emailPattern = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    /**
     * 敏感数据过滤
     * TODO 简陋
     */
    @Override
    public SensitiveDataFilterResult filter(String content) {
        String safe = content == null ? "" : content;
        List<String> categories = new ArrayList<>();
        String next = apiKeyPattern.matcher(safe).replaceAll("[API_KEY_REDACTED]");
        if (!next.equals(safe)) categories.add("api_key");
        safe = next;
        next = idCardPattern.matcher(safe).replaceAll("[ID_CARD_REDACTED]");
        if (!next.equals(safe)) categories.add("id_card");
        safe = next;
        next = phonePattern.matcher(safe).replaceAll("[PHONE_REDACTED]");
        if (!next.equals(safe)) categories.add("phone");
        safe = next;
        next = emailPattern.matcher(safe).replaceAll("[EMAIL_REDACTED]");
        if (!next.equals(safe)) categories.add("email");
        return new SensitiveDataFilterResult(safe, categories);
    }
}
