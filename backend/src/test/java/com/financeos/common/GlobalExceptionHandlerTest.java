package com.financeos.common;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionWith404ReturnsNotFound() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException(404, "流水不存在"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(404);
        assertThat(response.getBody().message()).isEqualTo("流水不存在");
    }

    @Test
    void methodArgumentNotValidReturnsBadRequestWithFirstValidationMessage() throws Exception {
        MethodArgumentNotValidException exception = methodArgumentNotValidException("账户不能为空");

        ResponseEntity<ApiResponse<Void>> response = handler.handleMethodArgumentNotValidException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("账户不能为空");
    }

    @Test
    void constraintViolationReturnsBadRequest() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleConstraintViolationException(new ConstraintViolationException("参数校验失败", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("参数校验失败");
    }

    @Test
    void missingRequestParameterReturnsBadRequest() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMissingServletRequestParameterException(
                        new MissingServletRequestParameterException("page", "int"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("缺少必要请求参数");
    }

    @Test
    void typeMismatchReturnsBadRequest() throws Exception {
        MethodParameter methodParameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", TestRequest.class), 0);
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMethodArgumentTypeMismatchException(
                        new MethodArgumentTypeMismatchException("abc", Long.class, "id", methodParameter, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("请求参数格式错误");
    }

    @Test
    void jsonParseErrorReturnsBadRequest() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleHttpMessageNotReadableException(new HttpMessageNotReadableException(
                        "bad json",
                        new MockHttpInputMessage("{".getBytes(StandardCharsets.UTF_8))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(400);
        assertThat(response.getBody().message()).isEqualTo("请求体格式错误");
    }

    @Test
    void unknownExceptionReturnsInternalServerError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleException(new RuntimeException("unexpected failure"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(500);
        assertThat(response.getBody().message()).isEqualTo("服务器内部错误");
    }

    private MethodArgumentNotValidException methodArgumentNotValidException(String message) throws Exception {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummy", TestRequest.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        TestRequest target = new TestRequest();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "request");
        bindingResult.addError(new FieldError("request", "accountId", message));
        return new MethodArgumentNotValidException(methodParameter, bindingResult);
    }

    @SuppressWarnings("unused")
    private void dummy(TestRequest request) {
    }

    private static class TestRequest {
        @SuppressWarnings("unused")
        private Long accountId;
    }
}
