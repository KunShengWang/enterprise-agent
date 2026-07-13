package com.agent.platform.guardrail;

public interface SensitiveDataFilter {

    /**
     * 敏感数据过滤
     */
    SensitiveDataFilterResult filter(String content);
}
