package com.financeos.module.asset.marketdata.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataConfig {
    private static final String TWELVE_DATA_BASE_URL = "https://api.twelvedata.com";

    @Bean("twelveDataRestClient")
    RestClient twelveDataRestClient(RestClient.Builder builder, MarketDataProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder
                .requestFactory(requestFactory)
                .baseUrl(TWELVE_DATA_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "apikey " + properties.getApiKey())
                .build();
    }
}
