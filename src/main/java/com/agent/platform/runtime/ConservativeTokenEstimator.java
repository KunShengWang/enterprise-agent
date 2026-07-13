package com.agent.platform.runtime;

import org.springframework.stereotype.Component;

/**
 * Provider 未返回 Token Usage 时的保守估算器，只用于预算保护，不用于效果判断。
 */
@Component
public class ConservativeTokenEstimator implements TokenEstimator {

    @Override
    public long estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long ascii = 0;
        long nonAscii = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) <= 0x7F) {
                ascii++;
            }
            else {
                nonAscii++;
            }
        }
        return Math.max(1, (ascii + 3) / 4 + nonAscii);
    }
}
