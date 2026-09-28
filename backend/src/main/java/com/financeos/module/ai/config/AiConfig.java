package com.financeos.module.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
    @Bean("financeAiRestClient")
    RestClient financeAiRestClient(RestClient.Builder builder, AiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration connectTimeout = properties.isEnabled() ? properties.getConnectTimeout() : Duration.ofSeconds(3);
        Duration readTimeout = properties.isEnabled() ? properties.getReadTimeout() : Duration.ofSeconds(30);
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return builder.requestFactory(requestFactory).build();
    }
}
