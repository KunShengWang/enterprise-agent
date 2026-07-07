package com.agent.platform.memory;

import java.time.Instant;
import java.util.List;

public record UserProfile(
        String userId,
        List<UserProfileItem> items,
        Instant updatedAt
) {

    public UserProfile {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static UserProfile empty(String userId) {
        return new UserProfile(userId, List.of(), null);
    }
}
