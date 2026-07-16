package com.financeos.module.ledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.common.PageResult;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import com.financeos.module.ledger.service.TransactionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private TransactionService transactionService;

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
    void pageWithoutAuthenticationReturnsUnifiedUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/page"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(transactionService);
    }

    @Test
    void pageForwardsAllFiltersAndReturnsTypedPageResponse() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 7, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 7, 31, 23, 59, 59);
        when(transactionService.pageByUser(USER_ID, 2, 30, ACCOUNT_ID, CATEGORY_ID, "INCOME", start, end))
                .thenReturn(new PageResult<>(List.of(transactionResponse(99L, "INCOME", "123.45")), 1L, 2, 30));

        MvcResult result = mockMvc.perform(get("/api/v1/transactions/page")
                        .param("page", "2")
                        .param("size", "30")
                        .param("accountId", ACCOUNT_ID.toString())
                        .param("categoryId", CATEGORY_ID.toString())
                        .param("type", "INCOME")
                        .param("start", "2026-07-01T00:00:00")
                        .param("end", "2026-07-31T23:59:59")
                        .with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.page").isNumber())
                .andExpect(jsonPath("$.data.size").isNumber())
                .andExpect(jsonPath("$.data.records[0].amount").isNumber())
                .andExpect(jsonPath("$.data.records[0].transactedAt").value("2026-07-16T10:30:45"))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-07-16T10:31:45"))
                .andExpect(jsonPath("$.data.records[0].updatedAt").value("2026-07-16T10:32:45"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/records/0/amount").decimalValue()).isEqualByComparingTo("123.45");
        verify(transactionService).pageByUser(USER_ID, 2, 30, ACCOUNT_ID, CATEGORY_ID, "INCOME", start, end);
    }

    @Test
    void pageRejectsUnparseableDateWithoutCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/page")
                        .param("start", "not-a-date")
                        .with(authentication(currentUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(transactionService);
    }

    @Test
    void getMapsMissingOrForeignTransactionToNotFoundForCurrentUser() throws Exception {
        when(transactionService.getById(USER_ID, 99L)).thenThrow(new BusinessException(404, "流水不存在"));

        mockMvc.perform(get("/api/v1/transactions/{id}", 99L).with(authentication(currentUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("流水不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(transactionService).getById(USER_ID, 99L);
    }

    @Test
    void createReturnsTransactionAndForwardsCompleteValidatedRequest() throws Exception {
        when(transactionService.create(eq(USER_ID), any(TransactionRequest.class)))
                .thenReturn(transactionResponse(99L, "INCOME", "123.45"));

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("INCOME", "123.45", "salary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.accountId").isNumber())
                .andExpect(jsonPath("$.data.categoryId").isNumber())
                .andExpect(jsonPath("$.data.amount").isNumber())
                .andExpect(jsonPath("$.data.transactedAt").value("2026-07-16T10:30:45"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/amount").decimalValue()).isEqualByComparingTo("123.45");
        ArgumentCaptor<TransactionRequest> requestCaptor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).create(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(transactionRequest("INCOME", "123.45", "salary"));
    }

    @Test
    void createRejectsMissingAmountWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":7,"categoryId":8,"type":"INCOME","currency":"CNY","transactedAt":"2026-07-16T10:30:45"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(transactionService);
    }

    @Test
    void createMapsBusinessRuleFailureToUnifiedBadRequest() throws Exception {
        when(transactionService.create(eq(USER_ID), any(TransactionRequest.class)))
                .thenThrow(new BusinessException(400, "当前版本暂不支持该流水类型"));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("TRANSFER", "123.45", "transfer")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("当前版本暂不支持该流水类型"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void updateReturnsTransactionAndForwardsCompleteValidatedRequest() throws Exception {
        when(transactionService.update(eq(USER_ID), eq(99L), any(TransactionRequest.class)))
                .thenReturn(transactionResponse(99L, "EXPENSE", "40.00"));

        MvcResult result = mockMvc.perform(put("/api/v1/transactions/{id}", 99L)
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("EXPENSE", "40.00", "groceries")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.amount").isNumber())
                .andExpect(jsonPath("$.data.transactedAt").value("2026-07-16T10:30:45"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-07-16T10:31:45"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-07-16T10:32:45"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/amount").decimalValue()).isEqualByComparingTo("40.00");
        ArgumentCaptor<TransactionRequest> requestCaptor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).update(eq(USER_ID), eq(99L), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(transactionRequest("EXPENSE", "40.00", "groceries"));
    }

    @Test
    void deleteReturnsSuccessAndForwardsCurrentUserAndTransactionId() throws Exception {
        mockMvc.perform(delete("/api/v1/transactions/{id}", 99L).with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(transactionService).delete(USER_ID, 99L);
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }

    private String validRequestJson(String type, String amount, String description) {
        return """
                {"accountId":7,"categoryId":8,"type":"%s","amount":%s,"currency":"CNY","description":"%s","transactedAt":"2026-07-16T10:30:45"}
                """.formatted(type, amount, description);
    }

    private TransactionRequest transactionRequest(String type, String amount, String description) {
        return new TransactionRequest(ACCOUNT_ID, CATEGORY_ID, type, new BigDecimal(amount), "CNY", description, TRANSACTED_AT);
    }

    private TransactionResponse transactionResponse(Long id, String type, String amount) {
        return new TransactionResponse(id, ACCOUNT_ID, CATEGORY_ID, type, new BigDecimal(amount), "CNY", "salary",
                TRANSACTED_AT, LocalDateTime.of(2026, 7, 16, 10, 31, 45), LocalDateTime.of(2026, 7, 16, 10, 32, 45));
    }
}
