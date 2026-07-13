package com.agent.platform.guardrail;

import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 在检测前统一 Unicode、控制字符、URL 编码和可疑 Base64 包装，降低简单混淆绕过。
 */
@Component
public class PromptInputNormalizer {

    public NormalizedPrompt normalize(String input) {
        String original = input == null ? "" : input;
        String canonical = canonicalize(original);
        List<String> variants = new ArrayList<>();
        variants.add(canonical);
        String urlDecoded = decodeUrl(canonical);
        if (!urlDecoded.equals(canonical)) {
            variants.add(canonicalize(urlDecoded));
        }
        for (String token : canonical.split("\\s+")) {
            String decoded = decodeBase64Token(token);
            if (decoded != null) {
                variants.add(canonicalize(decoded));
            }
        }
        return new NormalizedPrompt(original, canonical, variants.stream().distinct().toList());
    }

    private String canonicalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF]", "")
                .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ")
                .replaceAll("[ \\t]+", " ")
                .trim();
    }

    private String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private String decodeBase64Token(String token) {
        if (token == null || token.length() < 24 || token.length() > 8_192
                || !token.matches("[A-Za-z0-9+/=_-]+")) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(token);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            long printable = decoded.chars().filter(character -> !Character.isISOControl(character)).count();
            return decoded.isBlank() || printable < decoded.length() * 0.85 ? null : decoded;
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
