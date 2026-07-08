package com.agent.platform.resilience;

import com.agent.platform.config.ResilienceProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class InMemoryRateLimitService implements RateLimitService {

    private static final long WINDOW_MILLIS = 60_000;

    private final ResilienceProperties properties;

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public InMemoryRateLimitService(ResilienceProperties properties) {
        this.properties = properties;
    }

    @Override
    public RateLimitResult acquire(String key) {
        if (!properties.getRateLimit().isEnabled()) {
            return new RateLimitResult(true, key, Integer.MAX_VALUE, Integer.MAX_VALUE, System.currentTimeMillis() + WINDOW_MILLIS);
        }
        String effectiveKey = key == null || key.isBlank() ? "anonymous" : key.trim();
        int limit = Math.max(1, properties.getRateLimit().getMaxRequestsPerMinute());
        long now = System.currentTimeMillis();
        Window window = windows.compute(effectiveKey, (ignored, current) -> {
            if (current == null || now >= current.resetAtMillis) {
                return new Window(now + WINDOW_MILLIS, 1);
            }
            current.count++;
            return current;
        });
        boolean allowed = window.count <= limit;
        int remaining = Math.max(0, limit - window.count);
        return new RateLimitResult(allowed, effectiveKey, limit, remaining, window.resetAtMillis);
    }

    private static final class Window {

        private final long resetAtMillis;

        private int count;

        private Window(long resetAtMillis, int count) {
            this.resetAtMillis = resetAtMillis;
            this.count = count;
        }
    }
}
