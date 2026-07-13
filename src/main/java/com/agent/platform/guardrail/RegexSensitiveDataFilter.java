package com.agent.platform.guardrail;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RegexSensitiveDataFilter implements SensitiveDataFilter {

    private static final Pattern API_KEY = Pattern.compile("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{8,}(?![A-Za-z0-9])");

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");

    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    private static final Pattern PAYMENT_CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");

    @Override
    public SensitiveDataFilterResult filter(String content) {
        String safe = content == null ? "" : content;
        Set<String> categories = new LinkedHashSet<>();
        safe = redact(API_KEY, safe, ignored -> true, "[API_KEY_REDACTED]", "api_key", categories);
        safe = redact(ID_CARD, safe, ignored -> true, "[ID_CARD_REDACTED]", "id_card", categories);
        safe = redact(PHONE, safe, ignored -> true, "[PHONE_REDACTED]", "phone", categories);
        safe = redact(EMAIL, safe, ignored -> true, "[EMAIL_REDACTED]", "email", categories);
        safe = redact(PAYMENT_CARD, safe, this::passesLuhn,
                "[PAYMENT_CARD_REDACTED]", "payment_card", categories);
        return new SensitiveDataFilterResult(safe, List.copyOf(categories));
    }

    private String redact(Pattern pattern,
                          String content,
                          Predicate<String> validator,
                          String replacement,
                          String category,
                          Set<String> categories) {
        Matcher matcher = pattern.matcher(content);
        StringBuffer result = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!validator.test(candidate)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(candidate));
                continue;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            changed = true;
        }
        matcher.appendTail(result);
        if (changed) {
            categories.add(category);
        }
        return result.toString();
    }

    private boolean passesLuhn(String value) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
