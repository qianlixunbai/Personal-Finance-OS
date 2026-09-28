package com.financeos.module.ai.provider;

public enum AiErrorType {
    DISABLED("AI provider is disabled"),
    CONFIGURATION("AI provider configuration is invalid"),
    INVALID_REQUEST("AI request is invalid"),
    AUTHENTICATION("AI provider authentication failed"),
    RATE_LIMITED("AI provider rate limit was reached"),
    TIMEOUT("AI provider request timed out"),
    TRANSPORT("AI provider request could not be completed"),
    UPSTREAM_ERROR("AI provider returned an unsuccessful response"),
    RESPONSE_FORMAT("AI provider response is invalid");

    private final String safeMessage;

    AiErrorType(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    public String getSafeMessage() {
        return safeMessage;
    }
}
