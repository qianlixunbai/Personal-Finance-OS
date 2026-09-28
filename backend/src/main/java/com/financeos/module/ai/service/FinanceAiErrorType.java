package com.financeos.module.ai.service;

public enum FinanceAiErrorType {
    INVALID_USER("Authenticated user is invalid"),
    INVALID_QUESTION("Question is required"),
    INVALID_TOOL_CALL("Finance AI tool call is invalid"),
    UNSUPPORTED_TOOL("Finance AI tool is not supported"),
    INVALID_TOOL_ARGUMENTS("Finance AI tool arguments are invalid"),
    TOOL_BUDGET_EXCEEDED("Finance AI tool call budget was exceeded"),
    TOOL_ROUND_LIMIT_EXCEEDED("Finance AI tool round limit was exceeded"),
    FINANCE_READ_FAILED("Finance data could not be loaded"),
    FINANCE_SYSTEM_ERROR("Finance data request could not be completed"),
    FINANCE_SERIALIZATION_ERROR("Finance data response could not be prepared"),
    PROVIDER_ERROR("AI provider could not complete the request"),
    INVALID_PROVIDER_RESPONSE("AI provider response is invalid");

    private final String safeMessage;

    FinanceAiErrorType(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    public String getSafeMessage() {
        return safeMessage;
    }
}
