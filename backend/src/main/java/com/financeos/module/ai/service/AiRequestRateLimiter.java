package com.financeos.module.ai.service;

import com.financeos.module.ai.config.AiProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Component
public class AiRequestRateLimiter {
    private final AiProperties properties;
    private final Clock clock;
    private Instant windowStart;
    private final Map<Long, Integer> userCounts = new HashMap<>();

    public AiRequestRateLimiter(AiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(Long userId) {
        if (userId == null) {
            return false;
        }

        Instant currentWindow = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        if (!currentWindow.equals(windowStart)) {
            windowStart = currentWindow;
            userCounts.clear();
        }

        int limit = properties.getUserRequestLimitPerMinute();
        int count = userCounts.getOrDefault(userId, 0);
        if (count >= limit) {
            return false;
        }
        userCounts.put(userId, count + 1);
        return true;
    }
}
