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

class DataSourceConfigurationTest {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/finance_os";

    @Test
    void datasourceUrlUsesLocalhostDefaultWhenDbUrlIsUnset() throws IOException {
        assertThat(resolveDataSourceUrl(Map.of())).isEqualTo(DEFAULT_URL);
    }

    @Test
    void datasourceUrlUsesCompleteDbUrlEnvironmentValueWhenPresent() throws IOException {
        String configuredUrl = "jdbc:postgresql://db.example.internal:6432/finance_os?sslmode=require";

        assertThat(resolveDataSourceUrl(Map.of("DB_URL", configuredUrl))).isEqualTo(configuredUrl);
    }

    @Test
    void e2eProfileUsesTheDedicatedDatabaseWithoutDefaultFallback() throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("application-e2e.yml", new ClassPathResource("application-e2e.yml"));
        yamlSources.forEach(propertySources::addLast);

        assertThat(new PropertySourcesPropertyResolver(propertySources).getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/finance_os_e2e");
    }

    private String resolveDataSourceUrl(Map<String, Object> environmentValues) throws IOException {
        MutablePropertySources propertySources = new MutablePropertySources();
        propertySources.addFirst(new MapPropertySource("test-environment", environmentValues));

        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        yamlSources.forEach(propertySources::addLast);

        return new PropertySourcesPropertyResolver(propertySources).getProperty("spring.datasource.url");
    }
}
