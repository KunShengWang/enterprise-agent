package com.agent.platform.router;

import java.util.Map;

public record IntentRoute(
        IntentType type,
        String reason,
        Map<String, Object> slots
) {

    public IntentRoute {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }
}
