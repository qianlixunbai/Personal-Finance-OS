package com.financeos.module.ai.provider.cloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.financeos.module.ai.provider.AiProvider;
import com.financeos.module.ai.provider.AiProviderException;
import com.financeos.module.ai.provider.AiToolCallingProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Component
public class CloudAiProvider implements AiProvider, AiToolCallingProvider {
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final AiProperties properties;
    private final ObjectMapper objectMapper;

    public CloudAiProvider(@Qualifier("financeAiRestClient") RestClient restClient,
                           AiProperties properties,
                           ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiResponse generate(AiRequest request) {
        if (!properties.isEnabled()) {
            throw new AiProviderException(AiErrorType.DISABLED);
        }

        if (request == null || !StringUtils.hasText(request.prompt())) {
            throw new AiProviderException(AiErrorType.INVALID_REQUEST);
        }

        String responseBody = sendRequest(request.prompt());
        return parseResponse(responseBody);
    }

    @Override
    public AiToolResponse next(AiToolRequest request) {
        if (!properties.isEnabled()) {
            throw new AiProviderException(AiErrorType.DISABLED);
        }

        CloudToolChatRequest cloudRequest = toCloudToolChatRequest(request);
        return parseToolResponse(sendToolRequest(cloudRequest));
    }

    private String sendRequest(String prompt) {
        try {
            String responseBody = restClient.post()
                    .uri(chatCompletionsUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .body(new CloudChatRequest(properties.getModel(), List.of(new CloudMessage("user", prompt))))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw exceptionForStatus(response.getStatusCode());
                    })
                    .body(String.class);
            if (!StringUtils.hasText(responseBody)) {
                throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
            }
            return responseBody;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new AiProviderException(isTimeout(exception) ? AiErrorType.TIMEOUT : AiErrorType.TRANSPORT);
        } catch (RestClientException exception) {
            throw new AiProviderException(isTimeout(exception) ? AiErrorType.TIMEOUT : AiErrorType.TRANSPORT);
        }
    }

    private String sendToolRequest(CloudToolChatRequest cloudRequest) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(cloudRequest);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new AiProviderException(AiErrorType.INVALID_REQUEST);
        }

        try {
            String responseBody = restClient.post()
                    .uri(chatCompletionsUri())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw exceptionForStatus(response.getStatusCode());
                    })
                    .body(String.class);
            if (!StringUtils.hasText(responseBody)) {
                throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
            }
            return responseBody;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new AiProviderException(isTimeout(exception) ? AiErrorType.TIMEOUT : AiErrorType.TRANSPORT);
        } catch (RestClientException exception) {
            throw new AiProviderException(isTimeout(exception) ? AiErrorType.TIMEOUT : AiErrorType.TRANSPORT);
        }
    }

    private URI chatCompletionsUri() {
        return URI.create(properties.getBaseUrl() + CHAT_COMPLETIONS_PATH);
    }

    private AiResponse parseResponse(String responseBody) {
        CloudChatResponse payload;
        try {
            payload = objectMapper.readValue(responseBody, CloudChatResponse.class);
        } catch (JsonProcessingException exception) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }

        if (payload == null || payload.choices() == null || payload.choices().isEmpty()) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }
        CloudChoice firstChoice = payload.choices().get(0);
        String content = firstChoice == null || firstChoice.message() == null
                ? null
                : firstChoice.message().content();
        if (!StringUtils.hasText(content)) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }
        return new AiResponse(content);
    }

    private CloudToolChatRequest toCloudToolChatRequest(AiToolRequest request) {
        if (request == null
                || request.messages() == null
                || request.messages().isEmpty()
                || request.tools() == null
                || request.tools().isEmpty()) {
            throw new AiProviderException(AiErrorType.INVALID_REQUEST);
        }

        List<CloudToolChatMessage> messages = new ArrayList<>(request.messages().size());
        Set<String> toolCallIds = new HashSet<>();
        Set<String> pendingToolResults = new HashSet<>();
        for (AiToolMessage message : request.messages()) {
            if (message == null || message.role() == null) {
                throw new AiProviderException(AiErrorType.INVALID_REQUEST);
            }

            switch (message.role()) {
                case SYSTEM, USER -> {
                    if (!StringUtils.hasText(message.content()) || message.toolCallId() != null || message.toolCalls() != null) {
                        throw new AiProviderException(AiErrorType.INVALID_REQUEST);
                    }
                    messages.add(new CloudToolChatMessage(
                            message.role().name().toLowerCase(Locale.ROOT), message.content(), null, null));
                }
                case ASSISTANT -> {
                    if (message.toolCallId() != null || (message.toolCalls() != null && message.toolCalls().isEmpty())) {
                        throw new AiProviderException(AiErrorType.INVALID_REQUEST);
                    }
                    List<CloudToolCall> toolCalls = null;
                    if (message.toolCalls() != null) {
                        toolCalls = new ArrayList<>(message.toolCalls().size());
                        for (AiToolCall toolCall : message.toolCalls()) {
                            CloudToolCall cloudToolCall = toCloudToolCall(toolCall);
                            if (!toolCallIds.add(cloudToolCall.id())) {
                                throw new AiProviderException(AiErrorType.INVALID_REQUEST);
                            }
                            pendingToolResults.add(cloudToolCall.id());
                            toolCalls.add(cloudToolCall);
                        }
                    } else if (!StringUtils.hasText(message.content())) {
                        throw new AiProviderException(AiErrorType.INVALID_REQUEST);
                    }
                    messages.add(new CloudToolChatMessage("assistant", message.content(), null, toolCalls));
                }
                case TOOL -> {
                    if (message.toolCalls() != null
                            || !StringUtils.hasText(message.toolCallId())
                            || message.content() == null
                            || !pendingToolResults.remove(message.toolCallId())) {
                        throw new AiProviderException(AiErrorType.INVALID_REQUEST);
                    }
                    messages.add(new CloudToolChatMessage("tool", message.content(), message.toolCallId(), null));
                }
            }
        }

        if (!pendingToolResults.isEmpty()) {
            throw new AiProviderException(AiErrorType.INVALID_REQUEST);
        }

        List<CloudToolDefinition> tools = new ArrayList<>(request.tools().size());
        Set<String> toolNames = new HashSet<>();
        for (AiToolDefinition tool : request.tools()) {
            if (tool == null || !StringUtils.hasText(tool.name()) || tool.parameters() == null || !toolNames.add(tool.name())) {
                throw new AiProviderException(AiErrorType.INVALID_REQUEST);
            }
            tools.add(new CloudToolDefinition("function", new CloudFunctionDefinition(
                    tool.name(), tool.description(), tool.parameters())));
        }

        return new CloudToolChatRequest(properties.getModel(), messages, tools, "auto");
    }

    private CloudToolCall toCloudToolCall(AiToolCall toolCall) {
        if (toolCall == null
                || !StringUtils.hasText(toolCall.id())
                || !StringUtils.hasText(toolCall.name())
                || !StringUtils.hasText(toolCall.argumentsJson())
                || !isJsonObject(toolCall.argumentsJson())) {
            throw new AiProviderException(AiErrorType.INVALID_REQUEST);
        }
        return new CloudToolCall(toolCall.id(), "function", new CloudFunctionCall(toolCall.name(), toolCall.argumentsJson()));
    }

    private AiToolResponse parseToolResponse(String responseBody) {
        JsonNode payload = parseJsonDocument(responseBody);
        if (payload == null || !payload.isObject()) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }
        JsonNode choices = payload.get("choices");
        if (choices == null || !choices.isArray() || choices.size() == 0) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }
        JsonNode firstChoice = choices.get(0);
        if (firstChoice == null || !firstChoice.isObject()) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }
        JsonNode message = firstChoice.get("message");
        if (message == null || !message.isObject()) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }

        String content = null;
        JsonNode contentNode = message.get("content");
        if (contentNode != null && !contentNode.isNull()) {
            if (!contentNode.isTextual()) {
                throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
            }
            content = contentNode.textValue();
        }

        List<AiToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && !toolCallsNode.isNull()) {
            if (!toolCallsNode.isArray() || toolCallsNode.size() == 0) {
                throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
            }
            Set<String> ids = new HashSet<>();
            for (JsonNode toolCall : toolCallsNode) {
                if (toolCall == null || !toolCall.isObject() || !"function".equals(requiredText(toolCall, "type"))) {
                    throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
                }
                String id = requiredText(toolCall, "id");
                JsonNode function = toolCall.get("function");
                if (function == null || !function.isObject()) {
                    throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
                }
                String name = requiredText(function, "name");
                String arguments = requiredText(function, "arguments");
                if (!isJsonObject(arguments) || !ids.add(id)) {
                    throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
                }
                toolCalls.add(new AiToolCall(id, name, arguments));
            }
        }

        if (toolCalls.isEmpty() && !StringUtils.hasText(content)) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }
        return new AiToolResponse(content, List.copyOf(toolCalls));
    }

    private String requiredText(JsonNode object, String fieldName) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.textValue())) {
            throw new AiProviderException(AiErrorType.RESPONSE_FORMAT);
        }
        return value.textValue();
    }

    private boolean isJsonObject(String json) {
        JsonNode node = parseJsonDocument(json);
        return node != null && node.isObject();
    }

    private JsonNode parseJsonDocument(String json) {
        try {
            try (JsonParser parser = objectMapper.createParser(json)) {
                JsonNode node = objectMapper.readTree(parser);
                return node != null && parser.nextToken() == null ? node : null;
            }
        } catch (IOException exception) {
            return null;
        }
    }

    private AiProviderException exceptionForStatus(HttpStatusCode statusCode) {
        AiErrorType errorType = switch (statusCode.value()) {
            case 401, 403 -> AiErrorType.AUTHENTICATION;
            case 408 -> AiErrorType.TIMEOUT;
            case 429 -> AiErrorType.RATE_LIMITED;
            default -> AiErrorType.UPSTREAM_ERROR;
        };
        return new AiProviderException(errorType);
    }

    private boolean isTimeout(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException
                    || current instanceof InterruptedIOException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    private record CloudChatRequest(String model, List<CloudMessage> messages) {
    }

    private record CloudMessage(String role, String content) {
    }

    private record CloudToolChatRequest(String model,
                                        List<CloudToolChatMessage> messages,
                                        List<CloudToolDefinition> tools,
                                        @JsonProperty("tool_choice") String toolChoice) {
    }

    private record CloudToolChatMessage(String role,
                                        @JsonInclude(JsonInclude.Include.ALWAYS)
                                        String content,
                                        @JsonInclude(JsonInclude.Include.NON_NULL)
                                        @JsonProperty("tool_call_id") String toolCallId,
                                        @JsonInclude(JsonInclude.Include.NON_NULL)
                                        @JsonProperty("tool_calls") List<CloudToolCall> toolCalls) {
    }

    private record CloudToolDefinition(String type, CloudFunctionDefinition function) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CloudFunctionDefinition(String name, String description, Map<String, Object> parameters) {
    }

    private record CloudToolCall(String id, String type, CloudFunctionCall function) {
    }

    private record CloudFunctionCall(String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloudChatResponse(List<CloudChoice> choices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloudChoice(CloudResponseMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CloudResponseMessage(String content) {
    }
}
