package com.financeos.module.asset.marketdata.service;

import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Component
public class MarketQuoteRateLimiter {
    private final MarketDataProperties properties;
    private final Clock clock;
    private Instant windowStart;
    private int globalCount;
    private final Map<Long, Integer> userCounts = new HashMap<>();

    public MarketQuoteRateLimiter(MarketDataProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(Long userId) {
        Instant currentWindow = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        if (windowStart == null || !windowStart.equals(currentWindow)) {
            windowStart = currentWindow;
            globalCount = 0;
            userCounts.clear();
        }
        if (globalCount >= properties.getProviderRequestLimitPerMinute()
                || userCounts.getOrDefault(userId, 0) >= properties.getUserRequestLimitPerMinute()) {
            return false;
        }
        globalCount++;
        userCounts.merge(userId, 1, Integer::sum);
        return true;
    }
}
