package com.financeos.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unknownExceptionReturnsInternalServerError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleException(new RuntimeException("unexpected failure"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("服务器内部错误");
    }

    @ParameterizedTest
    @CsvSource({
            "23505, CONFLICT, 409",
            "23514, INTERNAL_SERVER_ERROR, 500",
            "22003, BAD_REQUEST, 400",
            "55P03, CONFLICT, 409",
            "40P01, CONFLICT, 409",
            "08006, SERVICE_UNAVAILABLE, 503"
    })
    void dataAccessExceptionsAreMappedBySqlState(String sqlState, HttpStatus expectedStatus, int expectedCode) {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleDataAccessException(dataAccessException(sqlState));

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(expectedCode);
    }

    @Test
    void constraintViolationResponsesStaySanitized() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleDataAccessException(dataAccessException("23505"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain("constraint", "boom", "uk_", "SQL");
    }

    private DataAccessException dataAccessException(String sqlState) {
        return new DataIntegrityViolationException("constraint boom", new SQLException("boom", sqlState));
    }

}
