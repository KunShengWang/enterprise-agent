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
        // 输入文本的标准化清洗——把用户输入里的不可见字符、零宽字符、控制字符全部干掉或统一，防止 LLM 被恶意 prompt 绕过护栏
        String canonical = canonicalize(original);
        List<String> variants = new ArrayList<>();
        variants.add(canonical);
        // 把 URL 编码的文本还原回原始内容。 防止攻击者用 %E6%94%BB%E5%87%BB 代替 攻击 绕过关键词检测
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

    /**
     * 输入文本的标准化清洗——把用户输入里的不可见字符、零宽字符、控制字符全部干掉或统一，防止 LLM 被恶意 prompt 绕过护栏。
     */
    private String canonicalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)// 把"长得很像不同字符"统一成一个字符，例如：ｇｒｅａｔ（全角字母）
                .replaceAll("[\\u200B-\\u200F\\u2060\\uFEFF]", "")//  删零宽字符
                .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ")// 删控制字符（保留换行和 Tab）
                .replaceAll("[ \\t]+", " ")// 合并多余空格/Tab，例如："hello     world"  → "hello world"
                .trim();
    }

    /**
     * 把 URL 编码的文本还原回原始内容。 防止攻击者用 %E6%94%BB%E5%87%BB 代替 攻击 绕过关键词检测
     */
    private String decodeUrl(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException exception) {
            return value;
        }
    }

    /**
     * 把 Base64 编码的文本还原
     */
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
