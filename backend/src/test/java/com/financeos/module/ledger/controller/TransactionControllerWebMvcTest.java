package com.financeos.module.ledger.controller;

import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import com.financeos.module.ledger.service.TransactionCommandService;
import com.financeos.module.ledger.service.TransactionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class TransactionControllerWebMvcTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final Long CATEGORY_ID = 8L;
    private static final LocalDateTime TRANSACTED_AT = LocalDateTime.of(2026, 7, 16, 10, 30, 45);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private TransactionService transactionService;

    @MockBean
    private TransactionCommandService transactionCommandService;

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
    void createForwardsIdempotencyKeyHeaderToTheCommandService() throws Exception {
        when(transactionCommandService.create(eq(USER_ID), eq("tx-key-1"), any(TransactionRequest.class)))
                .thenReturn(transactionResponse(99L, "EXPENSE", "40.00"));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(authentication(currentUser()))
                        .header("Idempotency-Key", "tx-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("EXPENSE", "40.00", "groceries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber());

        verify(transactionCommandService).create(eq(USER_ID), eq("tx-key-1"), any(TransactionRequest.class));
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }

    private String validRequestJson(String type, String amount, String description) {
        return """
                {"accountId":7,"categoryId":8,"type":"%s","amount":%s,"currency":"CNY","description":"%s","transactedAt":"2026-07-16T10:30:45"}
                """.formatted(type, amount, description);
    }

    private TransactionResponse transactionResponse(Long id, String type, String amount) {
        return new TransactionResponse(id, ACCOUNT_ID, CATEGORY_ID, type, new BigDecimal(amount), "CNY", "salary",
                TRANSACTED_AT, LocalDateTime.of(2026, 7, 16, 10, 31, 45), LocalDateTime.of(2026, 7, 16, 10, 32, 45));
    }
}
