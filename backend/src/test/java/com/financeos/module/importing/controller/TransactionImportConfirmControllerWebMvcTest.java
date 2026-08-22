package com.financeos.module.importing.controller;

import com.financeos.common.BusinessException;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.importing.service.TransactionImportConfirmService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionImportConfirmController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class TransactionImportConfirmControllerWebMvcTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private TransactionImportConfirmService confirmService;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void confirmReturnsStableDomainCodeAndRetryabilityForPreviewStale() throws Exception {
        when(confirmService.confirm(any(), any(), any(), any()))
                .thenThrow(new BusinessException(409, "IMPORT_PREVIEW_STALE"));

        mockMvc.perform(post("/api/v1/imports/transactions/{sessionId}/confirm", UUID.randomUUID())
                        .header("Idempotency-Key", "stale-key")
                        .contentType("application/json")
                        .content("{\"previewToken\":\"token\",\"acknowledgedWarningIds\":[]}")
                        .with(authentication(new UsernamePasswordAuthenticationToken(42L, null, Collections.emptyList()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IMPORT_PREVIEW_STALE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
