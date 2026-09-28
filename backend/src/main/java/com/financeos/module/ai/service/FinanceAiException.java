package com.financeos.module.ai.service;

import java.util.Objects;

public class FinanceAiException extends RuntimeException {
    private final FinanceAiErrorType errorType;

    public FinanceAiException(FinanceAiErrorType errorType) {
        super(Objects.requireNonNull(errorType, "errorType").getSafeMessage());
        this.errorType = errorType;
    }

    public FinanceAiErrorType getErrorType() {
        return errorType;
    }
}
