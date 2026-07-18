package com.financeos.module.asset.marketdata.fx.config;

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
}
