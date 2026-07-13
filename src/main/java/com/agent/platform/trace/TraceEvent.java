package com.agent.platform.trace;

import java.time.Instant;

public record TraceEvent(
        Instant occurredAt,// 发生时间
        String stage,// 阶段
        String detail// 详情
) {
}
