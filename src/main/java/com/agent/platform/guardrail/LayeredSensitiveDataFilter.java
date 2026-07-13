package com.agent.platform.guardrail;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地分层 DLP：固定格式、凭证键值上下文、Bearer/JWT、私钥块和高熵令牌。
 * 敏感原文不会为了分类而发送给外部模型。
 */
@Primary
@Component
public class LayeredSensitiveDataFilter implements SensitiveDataFilter {

    private static final Pattern CREDENTIAL_PAIR = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[-_ ]?key|access[-_ ]?key|client[-_ ]?secret)\\s*[:=]\\s*([\\\"']?)([^\\s,;\\\"']{6,})\\2"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PROVIDER_TOKEN = Pattern.compile(
            "\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{16,})\\b"
    );
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9+/=_-]{32,256}\\b");

    private final RegexSensitiveDataFilter formatFilter;

    public LayeredSensitiveDataFilter(RegexSensitiveDataFilter formatFilter) {
        this.formatFilter = formatFilter;
    }

    @Override
    public SensitiveDataFilterResult filter(String content) {
        SensitiveDataFilterResult formatted = formatFilter.filter(content);
        String safe = formatted.safeContent();
        Set<String> categories = new LinkedHashSet<>(formatted.categories());

        Redaction privateKey = replace(PRIVATE_KEY, safe, ignored -> "[PRIVATE_KEY_REDACTED]");
        safe = privateKey.content();
        addIfChanged(categories, "private_key", privateKey.changed());

        Redaction credential = replace(CREDENTIAL_PAIR, safe,
                matcher -> matcher.group(1) + "=[CREDENTIAL_REDACTED]");
        safe = credential.content();
        addIfChanged(categories, "credential", credential.changed());

        Redaction bearer = replace(BEARER, safe, ignored -> "Bearer [TOKEN_REDACTED]");
        safe = bearer.content();
        addIfChanged(categories, "bearer_token", bearer.changed());

        Redaction jwt = replace(JWT, safe, ignored -> "[JWT_REDACTED]");
        safe = jwt.content();
        addIfChanged(categories, "jwt", jwt.changed());

        Redaction provider = replace(PROVIDER_TOKEN, safe, ignored -> "[PROVIDER_TOKEN_REDACTED]");
        safe = provider.content();
        addIfChanged(categories, "provider_token", provider.changed());

        if (containsCredentialContext(safe)) {
            Redaction entropy = replace(LONG_TOKEN, safe, matcher ->
                    shannonEntropy(matcher.group()) >= 4.2 ? "[HIGH_ENTROPY_SECRET_REDACTED]" : matcher.group());
            safe = entropy.content();
            addIfChanged(categories, "high_entropy_secret", entropy.changed());
        }
        return new SensitiveDataFilterResult(safe, List.copyOf(categories));
    }

    private Redaction replace(Pattern pattern, String content, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(content == null ? "" : content);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String next = replacement.apply(matcher);
            if (!next.equals(matcher.group())) {
                changed = true;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(next));
        }
        matcher.appendTail(buffer);
        return new Redaction(buffer.toString(), changed);
    }

    private boolean containsCredentialContext(String content) {
        String normalized = content == null ? "" : content.toLowerCase(Locale.ROOT);
        return List.of("key", "token", "secret", "password", "密码", "密钥", "令牌")
                .stream()
                .anyMatch(normalized::contains);
    }

    private double shannonEntropy(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        Map<Integer, Long> frequencies = value.codePoints().boxed().collect(
                java.util.stream.Collectors.groupingBy(Function.identity(), java.util.stream.Collectors.counting())
        );
        double entropy = 0;
        for (long count : frequencies.values()) {
            double probability = (double) count / value.length();
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }

    private void addIfChanged(Set<String> categories, String category, boolean changed) {
        if (changed) {
            categories.add(category);
        }
    }

    private record Redaction(String content, boolean changed) {
    }
}
