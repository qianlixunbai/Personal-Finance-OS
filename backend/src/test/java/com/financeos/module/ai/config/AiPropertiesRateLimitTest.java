package com.financeos.module.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesRateLimitTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    @Test
    void defaultsToEightRequestsPerUserPerMinute() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiProperties.class).getUserRequestLimitPerMinute()).isEqualTo(8);
        });
    }

    @Test
    void bindsConfiguredLimit() {
        contextRunner.withPropertyValues("finance-ai.user-request-limit-per-minute=12").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiProperties.class).getUserRequestLimitPerMinute()).isEqualTo(12);
        });
    }

    @Test
    void rejectsNonPositiveLimitEvenWhenAiIsDisabled() {
        contextRunner.withPropertyValues("finance-ai.user-request-limit-per-minute=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("user-request-limit-per-minute must be positive");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class AiPropertiesConfiguration {
    }
}
