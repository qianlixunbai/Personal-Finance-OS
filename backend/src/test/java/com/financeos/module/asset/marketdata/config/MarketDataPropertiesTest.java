package com.financeos.module.asset.marketdata.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MarketDataConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder);

    @Test
    void allowsMissingApiKeyWhenMarketDataIsDisabled() {
        contextRunner.withPropertyValues("market-data.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsFastWhenMarketDataIsEnabledWithoutApiKey() {
        contextRunner.withPropertyValues("market-data.enabled=true", "market-data.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .isNotNull();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .hasMessageContaining("MARKET_DATA_API_KEY");
                });
    }
}
