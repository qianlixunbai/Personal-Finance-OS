package com.financeos.module.asset.marketdata.fx.service;

import com.financeos.module.asset.marketdata.fx.config.FxDataProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeRateRateLimiterTest {

    @Test
    void enforcesIndependentUserLimitsAndASharedGlobalLimit() {
        FxDataProperties properties = properties(2, 3);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T00:00:05Z"));
        ExchangeRateRateLimiter limiter = new ExchangeRateRateLimiter(properties, clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).isTrue();
        assertThat(limiter.tryAcquire(3L)).isFalse();
    }

    @Test
    void restoresQuotaWhenTheControlledMinuteWindowChanges() {
        FxDataProperties properties = properties(1, 1);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-19T00:00:59Z"));
        ExchangeRateRateLimiter limiter = new ExchangeRateRateLimiter(properties, clock);

        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(2L)).isFalse();
        clock.setInstant(Instant.parse("2026-07-19T00:01:00Z"));
        assertThat(limiter.tryAcquire(2L)).isTrue();
    }

    private FxDataProperties properties(int perUser, int global) {
        FxDataProperties properties = new FxDataProperties();
        properties.getRateLimit().setPerUserPerMinute(perUser);
        properties.getRateLimit().setGlobalPerMinute(global);
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
