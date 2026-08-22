package com.financeos.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data, String errorCode, Boolean retryable) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data, null, null);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(200, "success", null, null, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, null, null);
    }

    public static <T> ApiResponse<T> domainError(int status, String errorCode, boolean retryable) {
        return new ApiResponse<>(status, errorCode, null, errorCode, retryable);
    }
}
