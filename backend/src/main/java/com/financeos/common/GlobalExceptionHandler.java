package com.financeos.common;

import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProviderException;
import com.financeos.module.investment.migration.LegacyMigrationConsistencyException;
import com.financeos.module.investment.command.InvestmentWriteConsistencyException;
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
        log.error("Database operation failed", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "数据库服务暂时不可用"));
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

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("Business exception: {}", e.getMessage());
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
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(ApiResponse.error(500, "服务器内部错误"));
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
}
