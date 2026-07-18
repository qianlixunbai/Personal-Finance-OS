package com.financeos.module.asset.marketdata.fx.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "fx-data")
public class FxDataProperties {
    private boolean enabled;
    private String provider;
    private String baseUrl;
    private String apiKey;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration cacheTtl = Duration.ofMinutes(60);
    private RateLimit rateLimit = new RateLimit();

    @Getter
    @Setter
    public static class RateLimit {
        private int perUserPerMinute = 5;
        private int globalPerMinute = 30;
    }

    @PostConstruct
    void validateTimeouts() {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()
                || cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalStateException("FX provider durations must be positive");
        }
        if (rateLimit == null || rateLimit.perUserPerMinute <= 0 || rateLimit.globalPerMinute <= 0) {
            throw new IllegalStateException("FX rate limits must be positive");
        }
    }
}
