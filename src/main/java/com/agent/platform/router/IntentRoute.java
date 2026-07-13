package com.agent.platform.router;

import java.util.Map;

public record IntentRoute(
        IntentType type,// 路由类型
        String reason,// 选择的原因
        Map<String, Object> slots
) {

    public IntentRoute {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }
}
