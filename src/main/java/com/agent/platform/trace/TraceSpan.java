package com.agent.platform.trace;

import java.time.Instant;
import java.util.Map;

public record TraceSpan(
        String spanId,// 当前阶段的唯一 ID
        String traceId,// 所属 Agent Run 的 Trace ID
        String parentSpanId,// 父 Span ID，用于构建层级
        String name,// 阶段名称，如 llm.call
        TraceSpanKind kind,// 阶段类型，如 LLM、RAG、TOOL
        TraceSpanStatus status,
        String summary,// 阶段摘要
        Instant startedAt,// 开始时间
        Instant endedAt,// 结束时间
        long durationMs,// 执行耗时
        String input,// 阶段输入
        String output,// 阶段输出
        String error,// 失败原因
        Map<String, Object> attributes// 其他结构化属性
) {

    public TraceSpan {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
