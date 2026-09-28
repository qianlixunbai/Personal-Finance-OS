package com.financeos.module.ai.provider.cloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.ai.config.AiProperties;
import com.financeos.module.ai.dto.AiRequest;
import com.financeos.module.ai.dto.AiResponse;
import com.financeos.module.ai.provider.AiErrorType;
import com.financeos.module.ai.provider.AiProvider;
import com.financeos.module.ai.provider.AiProviderException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.TimeoutException;

@Component
public class CloudAiProvider implements AiProvider {
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
