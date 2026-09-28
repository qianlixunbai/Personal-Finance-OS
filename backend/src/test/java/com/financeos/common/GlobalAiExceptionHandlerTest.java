package com.financeos.common;

import com.financeos.module.ai.provider.AiErrorType;
import com.financeos.module.ai.provider.AiProviderException;
import com.financeos.module.ai.service.FinanceAiErrorType;
import com.financeos.module.ai.service.FinanceAiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalAiExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @EnumSource(AiErrorType.class)
    void mapsProviderErrorsToFixedSanitizedResponses(AiErrorType errorType) {
        ResponseEntity<ApiResponse<Void>> response = handler.handleAiProviderException(new AiProviderException(errorType));

        assertThat(response.getStatusCode()).isEqualTo(providerStatus(errorType));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(providerMessage(errorType));
    }

    @ParameterizedTest
    @EnumSource(FinanceAiErrorType.class)
    void mapsFinanceAiErrorsToFixedSanitizedResponses(FinanceAiErrorType errorType) {
        ResponseEntity<ApiResponse<Void>> response = handler.handleFinanceAiException(new FinanceAiException(errorType));

        assertThat(response.getStatusCode()).isEqualTo(financeStatus(errorType));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(financeMessage(errorType));
    }

    @Test
    void unexpectedAiEndpointErrorLogsOnlySafeExceptionType(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/v1/ai/ask");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ResponseEntity<ApiResponse<Void>> response = handler.handleException(
                new IllegalStateException("private question and finance amount marker"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(output.getOut()).contains("IllegalStateException")
                .doesNotContain("private question and finance amount marker");
    }

    private HttpStatus providerStatus(AiErrorType errorType) {
        return switch (errorType) {
            case RESPONSE_FORMAT -> HttpStatus.BAD_GATEWAY;
            case INVALID_REQUEST -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private String providerMessage(AiErrorType errorType) {
        return switch (errorType) {
            case DISABLED -> "AI 服务未启用";
            case RESPONSE_FORMAT -> "AI 服务返回了无效响应";
            case INVALID_REQUEST -> "服务器内部错误";
            default -> "AI 服务暂时不可用";
        };
    }

    private HttpStatus financeStatus(FinanceAiErrorType errorType) {
        return switch (errorType) {
            case INVALID_QUESTION -> HttpStatus.BAD_REQUEST;
            case INVALID_USER -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private String financeMessage(FinanceAiErrorType errorType) {
        return switch (errorType) {
            case INVALID_QUESTION -> "问题不能为空";
            case INVALID_USER -> "服务器内部错误";
            default -> "AI 分析暂时不可用";
        };
    }
}
