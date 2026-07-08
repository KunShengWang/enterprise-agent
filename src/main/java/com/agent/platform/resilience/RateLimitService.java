package com.agent.platform.resilience;

public interface RateLimitService {

    RateLimitResult acquire(String key);
}
