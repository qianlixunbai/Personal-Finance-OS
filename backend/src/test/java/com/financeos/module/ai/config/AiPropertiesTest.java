package com.financeos.module.ai.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesTest {
    private static final String SECRET_MARKER = "test-ai-secret-marker";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    @Test
    void aiIsDisabledWhenNoConfigurationIsProvided() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiProperties.class).isEnabled()).isFalse();
        });
    }

    @ParameterizedTest(name = "invalid enabled configuration fails closed: {0}")
    @MethodSource("invalidEnabledConfigurations")
    void enabledAiRejectsMissingOrInvalidConfiguration(String scenario, String[] values, String expectedMessage) {
        contextRunner.withPropertyValues(values).run(context -> {
            assertThat(context).hasFailed();
            String failureMessages = exceptionMessages(context.getStartupFailure());
            assertThat(failureMessages).contains(expectedMessage).doesNotContain(SECRET_MARKER);
        });
    }

    private static Stream<Arguments> invalidEnabledConfigurations() {
        String prefix = "finance-ai.enabled=true";
        return Stream.of(
                Arguments.of("missing API key", new String[]{
                        prefix, "finance-ai.provider=CLOUD", "finance-ai.base-url=https://provider.example/v1",
                        "finance-ai.model=test-model"
                }, "api-key is required"),
                Arguments.of("missing model", new String[]{
                        prefix, "finance-ai.provider=CLOUD", "finance-ai.base-url=https://provider.example/v1",
                        "finance-ai.api-key=" + SECRET_MARKER
                }, "model is required"),
                Arguments.of("missing base URL", new String[]{
                        prefix, "finance-ai.provider=CLOUD", "finance-ai.api-key=" + SECRET_MARKER,
                        "finance-ai.model=test-model"
                }, "base-url is required"),
                Arguments.of("insecure HTTP base URL", new String[]{
                        prefix, "finance-ai.provider=CLOUD", "finance-ai.base-url=http://provider.example/v1",
                        "finance-ai.api-key=" + SECRET_MARKER, "finance-ai.model=test-model"
                }, "base-url is invalid"),
                Arguments.of("unsupported provider", new String[]{
                        prefix, "finance-ai.provider=OLLAMA", "finance-ai.base-url=https://provider.example/v1",
                        "finance-ai.api-key=" + SECRET_MARKER, "finance-ai.model=test-model"
                }, "provider must be CLOUD"),
                Arguments.of("invalid base URL", new String[]{
                        prefix, "finance-ai.provider=CLOUD", "finance-ai.base-url=https://user:pass@provider.example/v1",
                        "finance-ai.api-key=" + SECRET_MARKER, "finance-ai.model=test-model"
                }, "base-url is invalid"),
                Arguments.of("non-positive read timeout", new String[]{
                        prefix, "finance-ai.provider=CLOUD", "finance-ai.base-url=https://provider.example/v1",
                        "finance-ai.api-key=" + SECRET_MARKER, "finance-ai.model=test-model",
                        "finance-ai.read-timeout=PT0S"
                }, "timeouts must be positive")
        );
    }

    private static String exceptionMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiProperties.class)
    static class AiPropertiesConfiguration {
    }
}
