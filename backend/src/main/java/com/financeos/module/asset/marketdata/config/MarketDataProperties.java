package com.financeos.module.asset.marketdata.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "market-data")
public class MarketDataProperties {
    private boolean enabled;
    private String apiKey;
    private String provider = "TWELVE_DATA";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration cacheTtl = Duration.ofMinutes(15);

    @PostConstruct
    void validateEnabledConfiguration() {
        if (enabled && !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("Market data is enabled but MARKET_DATA_API_KEY is not configured");
        }
    }
}
