package com.financeos.module.importing.controller;

import com.financeos.common.BusinessException;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.importing.dto.TransactionImportReceipt;
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

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionImportBatchController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class TransactionImportBatchControllerWebMvcTest {

    private static final Long USER_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private TransactionImportConfirmService confirmService;

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
    void statusQueryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/imports/transactions/batches/{batchId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(confirmService);
    }

    @Test
    void statusQueryReturnsOnlyTheCurrentUsersConfirmedBatch() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(confirmService.getReceipt(USER_ID, batchId)).thenReturn(new TransactionImportReceipt(
                UUID.randomUUID(), batchId, "CONFIRMED", "statement.csv", "a".repeat(64), 2, 2, 2, 0, 0,
                java.util.List.of(), java.util.List.of(), Instant.parse("2026-08-14T12:00:00Z"), "3.0", "b".repeat(64)));

        mockMvc.perform(get("/api/v1/imports/transactions/batches/{batchId}", batchId)
                        .with(authentication(new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.importBatchId").value(batchId.toString()))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        verify(confirmService).getReceipt(USER_ID, batchId);
    }

    @Test
    void statusQueryMapsForeignOrMissingBatchToNotFound() throws Exception {
        UUID batchId = UUID.randomUUID();
        when(confirmService.getReceipt(USER_ID, batchId)).thenThrow(new BusinessException(404, "Import batch not found"));

        mockMvc.perform(get("/api/v1/imports/transactions/batches/{batchId}", batchId)
                        .with(authentication(new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
