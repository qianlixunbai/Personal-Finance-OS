package com.financeos.common;

import com.financeos.module.ai.provider.AiErrorType;
import com.financeos.module.ai.provider.AiProviderException;
import com.financeos.module.ai.service.FinanceAiErrorType;
import com.financeos.module.ai.service.FinanceAiException;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProviderException;
import com.financeos.module.investment.migration.LegacyMigrationConsistencyException;
import com.financeos.module.investment.command.InvestmentWriteConsistencyException;
import com.financeos.module.investment.command.InvestmentReversalConsistencyException;
import com.financeos.module.investment.command.InvestmentReplacementConsistencyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ConcurrencyConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConcurrencyConflict(ConcurrencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(409, "并发操作冲突，请重试"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(DataAccessException e) {
        if (hasConcurrencySqlState(e)) {
            return handleConcurrencyConflict(new ConcurrencyConflictException());
        }
        // Client-caused constraint violations must not masquerade as "database unavailable":
        // a 503 tells the caller to retry a request that can never succeed.
        //
        // 23514 is deliberately NOT mapped to 400. This codebase uses CHECK constraints as the
        // last line of defence for server-side invariants (amount formulas, receipt shape), and
        // its own triggers raise 23514 for internal consistency failures. Reporting those as a
        // client error would hide real defects, so they fail closed as 500.
        switch (findSqlState(e)) {
            case "23505" -> {
                log.warn("Unique constraint violated", e);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.error(409, "资源已存在或与现有数据冲突"));
            }
            case "23514" -> {
                log.error("Check constraint violated", e);
                return ResponseEntity.internalServerError()
                        .body(ApiResponse.error(500, "数据一致性校验失败"));
            }
            case "22003" -> {
                log.warn("Numeric value out of range", e);
                return badRequest("数值超出允许范围");
            }
            default -> {
                log.error("Database operation failed", e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(503, "数据库服务暂时不可用"));
            }
        }
    }

    @ExceptionHandler(ExchangeRateProviderException.class)
    public ResponseEntity<ApiResponse<Void>> handleExchangeRateProviderException(ExchangeRateProviderException exception) {
        int status = switch (exception.getErrorType()) {
            case RATE_LIMITED -> 429;
            case RESPONSE_FORMAT, UNSUPPORTED_PAIR, INVALID_RATE, INVALID_TIME, CURRENCY_MISMATCH -> 502;
            default -> 503;
        };
        String message = switch (status) {
            case 429 -> "FX refresh limit has been reached";
            case 502 -> "FX provider returned an invalid response";
            default -> "FX data is temporarily unavailable";
        };
        return ResponseEntity.status(resolveHttpStatus(status)).body(ApiResponse.error(status, message));
    }

    @ExceptionHandler(AiProviderException.class)
    public ResponseEntity<ApiResponse<Void>> handleAiProviderException(AiProviderException exception) {
        AiErrorType errorType = exception.getErrorType();
        if (errorType == AiErrorType.DISABLED) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(503, "AI 服务未启用"));
        }
        if (errorType == AiErrorType.RESPONSE_FORMAT) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ApiResponse.error(502, "AI 服务返回了无效响应"));
        }
        if (errorType == AiErrorType.INVALID_REQUEST) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "服务器内部错误"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "AI 服务暂时不可用"));
    }

    @ExceptionHandler(FinanceAiException.class)
    public ResponseEntity<ApiResponse<Void>> handleFinanceAiException(FinanceAiException exception) {
        FinanceAiErrorType errorType = exception.getErrorType();
        if (errorType == FinanceAiErrorType.INVALID_QUESTION) {
            return badRequest("问题不能为空");
        }
        if (errorType == FinanceAiErrorType.INVALID_USER) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "服务器内部错误"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "AI 分析暂时不可用"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
        if (e.getMessage() != null && e.getMessage().startsWith("IMPORT_")) {
            return ResponseEntity.status(resolveHttpStatus(e.getCode()))
                    .body(ApiResponse.domainError(e.getCode(), e.getMessage(), importRetryable(e.getMessage())));
        }
        return ResponseEntity.status(resolveHttpStatus(e.getCode()))
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(LegacyMigrationConsistencyException.class)
    public ResponseEntity<ApiResponse<Void>> handleMigrationConsistencyException(LegacyMigrationConsistencyException e) {
        log.error("Migration consistency check failed", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error(500, "Migration consistency check failed"));
    }

    @ExceptionHandler(InvestmentWriteConsistencyException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvestmentWriteConsistencyException(InvestmentWriteConsistencyException e) {
        log.error("Investment write consistency check failed", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error(500, "Investment write consistency check failed"));
    }

    @ExceptionHandler(InvestmentReversalConsistencyException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvestmentReversalConsistencyException(InvestmentReversalConsistencyException e) {
        log.error("Investment reversal consistency check failed", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error(500, "Investment reversal consistency check failed"));
    }

    @ExceptionHandler(InvestmentReplacementConsistencyException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvestmentReplacementConsistencyException(InvestmentReplacementConsistencyException e) {
        log.error("Investment replacement consistency check failed", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error(500, "Investment replacement consistency check failed"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .filter(errorMessage -> errorMessage != null && !errorMessage.isBlank())
                .orElse("参数校验失败");
        log.warn("Validation failed: {}", message);
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        log.warn("Constraint violation: {}", e.getMessage());
        return badRequest("参数校验失败");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return badRequest("缺少必要请求参数");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("Request parameter type mismatch: {}", e.getName());
        return badRequest("请求参数格式错误");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("Request body is not readable");
        return badRequest("请求体格式错误");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        if (isAiAskEndpoint()) {
            log.error("Unexpected error on AI endpoint (type: {})", e.getClass().getSimpleName());
        } else {
            log.error("Unexpected error", e);
        }
        return ResponseEntity.internalServerError().body(ApiResponse.error(500, "服务器内部错误"));
    }

    private boolean isAiAskEndpoint() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        return "/api/v1/ai/ask".equals(request.getRequestURI())
                || "/api/v1/ai/ask".equals(request.getServletPath());
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    private HttpStatus resolveHttpStatus(int code) {
        return switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
            case 415 -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private boolean hasConcurrencySqlState(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException
                    && ("55P03".equals(sqlException.getSQLState()) || "40P01".equals(sqlException.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    private String findSqlState(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    private boolean importRetryable(String errorCode) {
        return switch (errorCode) {
            case "IMPORT_LOCK_CONFLICT" -> true;
            case "IMPORT_PREVIEW_STALE", "IMPORT_PREVIEW_EXPIRED", "IMPORT_DUPLICATE_EVIDENCE_CHANGED" -> true;
            default -> false;
        };
    }
}
