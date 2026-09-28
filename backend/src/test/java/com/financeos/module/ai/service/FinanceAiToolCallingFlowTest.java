package com.financeos.module.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.ai.config.AiProperties;
import com.financeos.module.ai.dto.AiResponse;
import com.financeos.module.ai.dto.toolcalling.AiToolCall;
import com.financeos.module.ai.dto.toolcalling.AiToolDefinition;
import com.financeos.module.ai.provider.cloud.CloudAiProvider;
import com.financeos.module.ai.tool.FinanceToolDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FinanceAiToolCallingFlowTest {
    private static final String BASE_URL = "https://provider.example/v1";
    private static final String API_KEY = "flow-test-secret-marker";
    private static final String COMPLETIONS_URL = BASE_URL + "/chat/completions";
    private static final Long AUTHENTICATED_USER_ID = 73L;
    private static final String QUESTION = "What is my accounting net worth?";
    private static final String FACT_JSON = "{\"currency\":\"CNY\",\"accountingNetWorth\":900.00}";
    private static final AiToolCall OVERVIEW_CALL = new AiToolCall(
            "call-overview", "getFinancialOverview", "{}");

    @Test
    void sendsToolCallExecutesReadDispatcherAndReturnsProviderFinalAnswer() {
        AiProperties properties = enabledProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CloudAiProvider provider = new CloudAiProvider(builder.build(), properties, new ObjectMapper());
        FinanceToolDispatcher dispatcher = mock(FinanceToolDispatcher.class);
        AiToolDefinition definition = new AiToolDefinition("getFinancialOverview",
                "Read accounting overview", Map.of("type", "object", "properties", Map.of()));
        when(dispatcher.definitions()).thenReturn(List.of(definition));
        when(dispatcher.execute(AUTHENTICATED_USER_ID, OVERVIEW_CALL)).thenReturn(FACT_JSON);

        server.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("flow-test-model"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value(QUESTION))
                .andExpect(jsonPath("$.tools[0].function.name").value("getFinancialOverview"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(AUTHENTICATED_USER_ID.toString()))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":null,"tool_calls":[{
                          "id":"call-overview",
                          "type":"function",
                          "function":{"name":"getFinancialOverview","arguments":"{}"}
                        }]}}]}
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(COMPLETIONS_URL))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.model").value("flow-test-model"))
                .andExpect(jsonPath("$.messages[2].role").value("assistant"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call-overview"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name")
                        .value("getFinancialOverview"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call-overview"))
                .andExpect(jsonPath("$.messages[3].content").value(FACT_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(AUTHENTICATED_USER_ID.toString()))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"Your accounting net worth is CNY 900."}}]}
                        """, MediaType.APPLICATION_JSON));

        FinanceAiOrchestrator orchestrator = new FinanceAiOrchestrator(provider, dispatcher);
        AiResponse response = orchestrator.ask(AUTHENTICATED_USER_ID, QUESTION);

        assertThat(response.content()).isEqualTo("Your accounting net worth is CNY 900.");
        verify(dispatcher, times(2)).definitions();
        verify(dispatcher).validate(OVERVIEW_CALL);
        verify(dispatcher).execute(AUTHENTICATED_USER_ID, OVERVIEW_CALL);
        server.verify();
    }

    private AiProperties enabledProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setProvider("CLOUD");
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey(API_KEY);
        properties.setModel("flow-test-model");
        properties.validateEnabledConfiguration();
        return properties;
    }
}
