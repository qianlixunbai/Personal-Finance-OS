package com.financeos.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataConfigurationTest {

    @Test
    void marketDataIsDisabledByDefaultAndUsesSafeProviderDefaults() throws IOException {
        PropertySourcesPropertyResolver resolver = resolverFor(Map.of());

        assertThat(resolver.getProperty("market-data.enabled", Boolean.class)).isFalse();
        assertThat(resolver.getProperty("market-data.provider")).isEqualTo("TWELVE_DATA");
        assertThat(resolver.getProperty("market-data.connect-timeout")).isEqualTo("2s");
        assertThat(resolver.getProperty("market-data.read-timeout")).isEqualTo("5s");
        assertThat(resolver.getProperty("market-data.cache-ttl")).isEqualTo("15m");
    }

    @Test
    void marketDataApiKeyComesFromBackendEnvironmentVariablePlaceholder() throws IOException {
        PropertySourcesPropertyResolver resolver = resolverFor(Map.of("MARKET_DATA_API_KEY", "test-api-key"));

        assertThat(resolver.getProperty("market-data.api-key")).isEqualTo("test-api-key");
    }

    private PropertySourcesPropertyResolver resolverFor(Map<String, Object> environmentValues) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("test-environment", environmentValues));
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        yamlSources.forEach(propertySources::addLast);
        return new PropertySourcesPropertyResolver(propertySources);
    }
}
