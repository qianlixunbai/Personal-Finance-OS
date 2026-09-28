package com.financeos.module.ai.provider;

import java.util.Objects;

public class AiProviderException extends RuntimeException {
    private final AiErrorType errorType;

    public AiProviderException(AiErrorType errorType) {
        super(Objects.requireNonNull(errorType, "errorType").getSafeMessage());
        this.errorType = errorType;
    }

    public AiErrorType getErrorType() {
        return errorType;
    }
}
