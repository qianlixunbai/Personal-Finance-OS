package com.financeos.module.ai.service;

import com.financeos.module.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiRequestRateLimiterTest {

    @Test
    void appliesLimitPerUserAndResetsAtTheNextMinute() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-28T12:34:50Z"));
        AiProperties properties = new AiProperties();
        properties.setUserRequestLimitPerMinute(2);
        AiRequestRateLimiter limiter = new AiRequestRateLimiter(properties, clock);

        assertThat(limiter.tryAcquire(101L)).isTrue();
        assertThat(limiter.tryAcquire(101L)).isTrue();
        assertThat(limiter.tryAcquire(101L)).isFalse();
        assertThat(limiter.tryAcquire(202L)).isTrue();

        clock.setInstant(Instant.parse("2026-09-28T12:35:00Z"));
        assertThat(limiter.tryAcquire(101L)).isTrue();
    }

    @Test
    void concurrentRequestsCannotExceedConfiguredUserLimit() throws Exception {
        AiProperties properties = new AiProperties();
        properties.setUserRequestLimitPerMinute(5);
        AiRequestRateLimiter limiter = new AiRequestRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-09-28T12:34:00Z"), ZoneId.of("UTC")));
        int requests = 32;
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return limiter.tryAcquire(101L);
                }));
            }
            ready.await();
            start.countDown();

            long accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(5);
        }
    }

    @Test
    void rejectsNullUserIdWithoutCreatingASharedAnonymousBucket() {
        AiProperties properties = new AiProperties();
        AiRequestRateLimiter limiter = new AiRequestRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-09-28T12:34:00Z"), ZoneId.of("UTC")));

        assertThat(limiter.tryAcquire(null)).isFalse();
        assertThat(limiter.tryAcquire(101L)).isTrue();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initialInstant) {
            this.instant = new AtomicReference<>(initialInstant);
        }

        private void setInstant(Instant newInstant) {
            instant.set(newInstant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
