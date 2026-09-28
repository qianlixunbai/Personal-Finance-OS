package com.financeos.module.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "finance-ai")
public class AiProperties {
    private boolean enabled;
    private String provider;
    private String baseUrl;
    private String apiKey;
    private String model;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(30);
    private int userRequestLimitPerMinute = 8;

    @PostConstruct
    public void validateEnabledConfiguration() {
        if (userRequestLimitPerMinute <= 0) {
            throw invalidConfiguration("user-request-limit-per-minute must be positive");
        }
        if (!enabled) {
            return;
        }

        if (!StringUtils.hasText(provider) || !"CLOUD".equalsIgnoreCase(provider.trim())) {
            throw invalidConfiguration("provider must be CLOUD");
        }
        provider = "CLOUD";

        if (!StringUtils.hasText(baseUrl)) {
            throw invalidConfiguration("base-url is required");
        }
        baseUrl = normalizeBaseUrl(baseUrl);

        if (!StringUtils.hasText(apiKey)) {
            throw invalidConfiguration("api-key is required");
        }
        if (containsControlCharacters(apiKey)) {
            throw invalidConfiguration("api-key is invalid");
        }
        if (!StringUtils.hasText(model)) {
            throw invalidConfiguration("model is required");
        }
        model = model.trim();
        if (containsControlCharacters(model)) {
            throw invalidConfiguration("model is invalid");
        }

        if (!isValidTimeout(connectTimeout) || !isValidTimeout(readTimeout)) {
            throw invalidConfiguration("timeouts must be positive and no greater than 2147483647ms");
        }
    }

    private String normalizeBaseUrl(String value) {
        String candidate = value.trim();
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)
                    || !StringUtils.hasText(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw invalidConfiguration("base-url is invalid");
            }
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration("base-url is invalid");
        }

        int end = candidate.length();
        while (end > 0 && candidate.charAt(end - 1) == '/') {
            end--;
        }
        return candidate.substring(0, end);
    }

    private boolean isValidTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return false;
        }
        try {
            long millis = timeout.toMillis();
            return millis > 0 && millis <= Integer.MAX_VALUE;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private boolean containsControlCharacters(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private IllegalStateException invalidConfiguration(String detail) {
        return new IllegalStateException("Finance AI configuration is invalid: " + detail);
    }
}
