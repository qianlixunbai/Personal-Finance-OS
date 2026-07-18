package com.financeos.module.asset.marketdata.fx.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FxDataPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FxDataConfig.class);

    @Test
    void defaultsToDisabledWithoutCreatingANetworkClient() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FxDataProperties.class).isEnabled()).isFalse();
            assertThat(context.getBeanNamesForType(java.net.http.HttpClient.class)).isEmpty();
        });
    }

    @Test
    void bindsFxConfigurationFromExternalProperties() {
        contextRunner.withPropertyValues(
                        "fx-data.enabled=true",
                        "fx-data.provider=EXAMPLE_FX",
                        "fx-data.base-url=https://fx.example.test",
                        "fx-data.api-key=test-only-key",
                        "fx-data.connect-timeout=3s",
                        "fx-data.read-timeout=7s")
                .run(context -> {
                    FxDataProperties properties = context.getBean(FxDataProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getProvider()).isEqualTo("EXAMPLE_FX");
                    assertThat(properties.getBaseUrl()).isEqualTo("https://fx.example.test");
                    assertThat(properties.getApiKey()).isEqualTo("test-only-key");
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(7));
                });
    }
}
