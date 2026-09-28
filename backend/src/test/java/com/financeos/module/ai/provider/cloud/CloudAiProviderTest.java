package com.financeos.module.ai.provider.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.ai.config.AiProperties;
import com.financeos.module.ai.dto.AiRequest;
import com.financeos.module.ai.dto.AiResponse;
import com.financeos.module.ai.dto.toolcalling.AiToolCall;
import com.financeos.module.ai.dto.toolcalling.AiToolDefinition;
import com.financeos.module.ai.dto.toolcalling.AiToolMessage;
import com.financeos.module.ai.dto.toolcalling.AiToolRequest;
import com.financeos.module.ai.dto.toolcalling.AiToolResponse;
import com.financeos.module.ai.provider.AiErrorType;
import com.financeos.module.ai.provider.AiProviderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class CloudAiProviderTest {
    private static final String BASE_URL = "https://provider.example/v1";
    private static final String API_KEY_MARKER = "test-ai-secret-marker";
    private static final String PROMPT_MARKER = "private prompt marker";
    private static final String CHAT_COMPLETIONS_URL = BASE_URL + "/chat/completions";

    @Test
    void parsesProviderResponseIntoFinanceOwnedResponseDto() throws Exception {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY_MARKER))
                .andExpect(content().json("""
                        {
                          "model": "test-model",
                          "messages": [{"role": "user", "content": "Return a short answer"}]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"A short answer"}}]}
                        """, MediaType.APPLICATION_JSON));

        AiResponse response = fixture.provider().generate(new AiRequest("Return a short answer"));

        assertThat(response).isEqualTo(new AiResponse("A short answer"));
        assertThat(new ObjectMapper().writeValueAsString(response)).isEqualTo("{\"content\":\"A short answer\"}");
        fixture.server().verify();
    }

    @Test
    void nextReturnsFinalAssistantTextAsFinanceOwnedToolResponse() {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY_MARKER))
                .andExpect(content().json("""
                        {
                          "model": "test-model",
                          "messages": [
                            {"role": "system", "content": "Use finance facts from tools"},
                            {"role": "user", "content": "How much did I save?"}
                          ],
                          "tools": [{
                            "type": "function",
                            "function": {
                              "name": "getFinancialOverview",
                              "description": "Read financial overview",
                              "parameters": {"type": "object", "properties": {}}
                            }
                          }]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"You saved 760 CNY."}}]}
                        """, MediaType.APPLICATION_JSON));

        AiToolResponse response = fixture.provider().next(requestWith(
                List.of(
                        new AiToolMessage(AiToolMessage.Role.SYSTEM,
                                "Use finance facts from tools", null, null),
                        new AiToolMessage(AiToolMessage.Role.USER,
                                "How much did I save?", null, null)),
                List.of(new AiToolDefinition("getFinancialOverview", "Read financial overview",
                        Map.of("type", "object", "properties", Map.of())))));

        assertThat(response).isEqualTo(new AiToolResponse("You saved 760 CNY.", List.of()));
        fixture.server().verify();
    }

    @Test
    void nextPreservesProviderToolCallIdNameAndRawArguments() {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "content": null,
                              "tool_calls": [{
                                "id": "call_cash_flow",
                                "type": "function",
                                "function": {
                                  "name": "getMonthlyCashFlow",
                                  "arguments": "{\\"fromMonth\\":\\"2025-01\\",\\"throughMonth\\":\\"2025-12\\"}"
                                }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        AiToolResponse response = fixture.provider().next(requestWith(
                List.of(new AiToolMessage(AiToolMessage.Role.USER, "Show cash flow", null, null)),
                List.of(minimalToolDefinition())));

        assertThat(response.content()).isNull();
        assertThat(response.toolCalls()).containsExactly(new AiToolCall(
                "call_cash_flow", "getMonthlyCashFlow",
                "{\"fromMonth\":\"2025-01\",\"throughMonth\":\"2025-12\"}"));
        fixture.server().verify();
    }

    @Test
    void nextSerializesAssistantToolCallsAndToolResultMessagesWhenContinuingConversation() {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andExpect(content().json("""
                        {
                          "model": "test-model",
                          "messages": [
                            {"role": "user", "content": "Show cash flow"},
                            {"role": "assistant", "tool_calls": [{
                              "id": "call_cash_flow",
                              "type": "function",
                              "function": {
                                "name": "getMonthlyCashFlow",
                                "arguments": "{\\"fromMonth\\":\\"2025-01\\",\\"throughMonth\\":\\"2025-01\\"}"
                              }
                            }]},
                            {"role": "tool", "tool_call_id": "call_cash_flow", "content": "{\\"currency\\":\\"CNY\\",\\"months\\":[]}"}
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"January had no activity."}}]}
                        """, MediaType.APPLICATION_JSON));

        AiToolResponse response = fixture.provider().next(requestWith(List.of(
                new AiToolMessage(AiToolMessage.Role.USER, "Show cash flow", null, null),
                new AiToolMessage(AiToolMessage.Role.ASSISTANT, null, null, List.of(new AiToolCall(
                        "call_cash_flow", "getMonthlyCashFlow",
                        "{\"fromMonth\":\"2025-01\",\"throughMonth\":\"2025-01\"}"))),
                new AiToolMessage(AiToolMessage.Role.TOOL,
                        "{\"currency\":\"CNY\",\"months\":[]}", "call_cash_flow", null)),
                List.of(minimalToolDefinition())));

        assertThat(response).isEqualTo(new AiToolResponse("January had no activity.", List.of()));
        fixture.server().verify();
    }

    @Test
    void disabledProviderFailsWithoutMakingAnHttpRequest() {
        AiProperties disabledProperties = new AiProperties();
        ProviderFixture fixture = fixture(disabledProperties);

        assertThatThrownBy(() -> fixture.provider().generate(new AiRequest("prompt")))
                .isInstanceOf(AiProviderException.class)
                .satisfies(exception -> assertThat(((AiProviderException) exception).getErrorType())
                        .isEqualTo(AiErrorType.DISABLED));

        fixture.server().verify();
    }

    @Test
    void disabledToolCallingProviderFailsWithoutMakingAnHttpRequest() {
        ProviderFixture fixture = fixture(new AiProperties());

        assertSanitizedToolFailure(fixture, AiErrorType.DISABLED, minimalToolRequest());

        fixture.server().verify();
    }

    @Test
    void mapsSocketTimeoutToStableTimeoutErrorWithoutRetainingTransportDetails() {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                        "request failed " + API_KEY_MARKER + " " + PROMPT_MARKER,
                        new SocketTimeoutException("read timeout " + API_KEY_MARKER));
                });

        assertSanitizedFailure(fixture, AiErrorType.TIMEOUT);
        fixture.server().verify();
    }

    @Test
    void mapsToolCallingSocketTimeoutWithoutExposingTransportDetails() {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "request failed " + API_KEY_MARKER + " " + PROMPT_MARKER,
                            new SocketTimeoutException("read timeout " + API_KEY_MARKER));
                });

        assertSanitizedToolFailure(fixture, AiErrorType.TIMEOUT, minimalToolRequest());
        fixture.server().verify();
    }

    @ParameterizedTest(name = "tool HTTP {0} maps to {1} without exposing provider details")
    @MethodSource("httpErrors")
    void mapsToolCallingHttpErrorsWithoutExposingCredentialsPromptOrResponseBody(
            HttpStatus status, AiErrorType expectedType) {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("upstream diagnostic " + API_KEY_MARKER + " " + PROMPT_MARKER));

        assertSanitizedToolFailure(fixture, expectedType,
                requestWith(List.of(new AiToolMessage(AiToolMessage.Role.USER,
                        PROMPT_MARKER, null, null)), List.of(minimalToolDefinition())));

        fixture.server().verify();
    }

    @ParameterizedTest(name = "HTTP {0} maps to {1} without exposing provider details")
    @MethodSource("httpErrors")
    void mapsHttpProviderErrorsWithoutExposingCredentialsPromptOrResponseBody(HttpStatus status,
                                                                              AiErrorType expectedType) {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("upstream diagnostic " + API_KEY_MARKER + " " + PROMPT_MARKER));

        assertSanitizedFailure(fixture, expectedType);
        fixture.server().verify();
    }

    private static Stream<Arguments> httpErrors() {
        return Stream.of(
                Arguments.of(HttpStatus.UNAUTHORIZED, AiErrorType.AUTHENTICATION),
                Arguments.of(HttpStatus.FORBIDDEN, AiErrorType.AUTHENTICATION),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, AiErrorType.RATE_LIMITED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, AiErrorType.UPSTREAM_ERROR)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{\"choices\":[]}"})
    void rejectsMalformedProviderResponseWithStableError(String body) {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertSanitizedFailure(fixture, AiErrorType.RESPONSE_FORMAT);
        fixture.server().verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"getMonthlyCashFlow\",\"arguments\":\"{}\"}}]}}]}",
            "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\" \",\"arguments\":\"{}\"}}]}}]}"
    })
    void rejectsMalformedToolCallingResponseWithStableSanitizedError(String body) {
        ProviderFixture fixture = fixture(enabledProperties());
        fixture.server().expect(requestTo(CHAT_COMPLETIONS_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertSanitizedToolFailure(fixture, AiErrorType.RESPONSE_FORMAT, minimalToolRequest());

        fixture.server().verify();
    }

    private void assertSanitizedFailure(ProviderFixture fixture, AiErrorType expectedType) {
        assertThatThrownBy(() -> fixture.provider().generate(new AiRequest(PROMPT_MARKER)))
                .isInstanceOf(AiProviderException.class)
                .satisfies(exception -> {
                    AiProviderException providerException = (AiProviderException) exception;
                    assertThat(providerException.getErrorType()).isEqualTo(expectedType);
                    assertThat(providerException.getMessage()).isEqualTo(expectedType.getSafeMessage());
                    assertThat(exceptionMessages(providerException))
                            .doesNotContain(API_KEY_MARKER, PROMPT_MARKER, "upstream diagnostic", "read timeout");
                    assertThat(providerException.getCause()).isNull();
                });
    }

    private void assertSanitizedToolFailure(ProviderFixture fixture,
                                           AiErrorType expectedType,
                                           AiToolRequest request) {
        assertThatThrownBy(() -> fixture.provider().next(request))
                .isInstanceOf(AiProviderException.class)
                .satisfies(exception -> {
                    AiProviderException providerException = (AiProviderException) exception;
                    assertThat(providerException.getErrorType()).isEqualTo(expectedType);
                    assertThat(providerException.getMessage()).isEqualTo(expectedType.getSafeMessage());
                    assertThat(exceptionMessages(providerException))
                            .doesNotContain(API_KEY_MARKER, PROMPT_MARKER, "upstream diagnostic", "read timeout");
                    assertThat(providerException.getCause()).isNull();
                });
    }

    private AiToolRequest minimalToolRequest() {
        return requestWith(List.of(new AiToolMessage(AiToolMessage.Role.USER,
                "Tell me a finance fact", null, null)), List.of(minimalToolDefinition()));
    }

    private AiToolDefinition minimalToolDefinition() {
        return new AiToolDefinition("getFinancialOverview", "Read financial overview",
                Map.of("type", "object", "properties", Map.of()));
    }

    private AiToolRequest requestWith(List<AiToolMessage> messages, List<AiToolDefinition> definitions) {
        return new AiToolRequest(messages, definitions);
    }

    private String exceptionMessages(Throwable exception) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }

    private AiProperties enabledProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setProvider(" cloud ");
        properties.setBaseUrl(BASE_URL + "/");
        properties.setApiKey(API_KEY_MARKER);
        properties.setModel("test-model");
        properties.validateEnabledConfiguration();
        return properties;
    }

    private ProviderFixture fixture(AiProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CloudAiProvider provider = new CloudAiProvider(builder.build(), properties, new ObjectMapper());
        return new ProviderFixture(provider, server);
    }

    private record ProviderFixture(CloudAiProvider provider, MockRestServiceServer server) {
    }
}
