package com.agent.platform.guardrail;

public interface SensitiveDataFilter {

    SensitiveDataFilterResult filter(String content);
}
