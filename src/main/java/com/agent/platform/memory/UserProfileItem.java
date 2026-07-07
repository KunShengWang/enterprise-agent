package com.agent.platform.memory;

import java.time.Instant;

public record UserProfileItem(
        String key,
        String value,
        String source,
        Instant updatedAt
) {
}
