package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

@Component
public class ExchangeRateRateLimiter {
    private final FxDataProperties properties;
    private final Clock clock;
    private Instant window;
    private int globalCount;
    private final Map<Long, Integer> userCounts = new HashMap<>();

    public ExchangeRateRateLimiter(FxDataProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(Long userId) {
        Instant current = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        if (!current.equals(window)) {
            window = current;
            globalCount = 0;
            userCounts.clear();
        }
        if (globalCount >= properties.getRateLimit().getGlobalPerMinute()
                || userCounts.getOrDefault(userId, 0) >= properties.getRateLimit().getPerUserPerMinute()) {
            return false;
        }
        globalCount++;
        userCounts.merge(userId, 1, Integer::sum);
        return true;
    }
}
