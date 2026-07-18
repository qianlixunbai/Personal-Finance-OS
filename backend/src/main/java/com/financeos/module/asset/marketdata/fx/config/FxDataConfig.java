package com.financeos.module.asset.marketdata.fx.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FxDataProperties.class)
public class FxDataConfig {
}
