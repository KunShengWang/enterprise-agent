package com.agent.platform.trace;

public enum TraceSpanKind {
    AGENT,
    MEMORY,
    GUARDRAIL,
    SKILL,
    ROUTER,
    QUERY_REWRITE,
    RAG,
    TOOL,
    APPROVAL,
    PROMPT,
    LLM,
    EVAL,
    ERROR,
    SYSTEM
}
