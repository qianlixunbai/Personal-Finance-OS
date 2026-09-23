package com.financeos.module.ledger.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.ledger.dto.TransferRequest;
import com.financeos.module.ledger.dto.TransferResponse;
import com.financeos.module.ledger.service.TransferService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class TransferControllerWebMvcTest {

    private static final Long USER_ID = 42L;
    private static final Long FROM_ACCOUNT_ID = 7L;
    private static final Long TO_ACCOUNT_ID = 9L;
    private static final LocalDateTime TRANSACTED_AT = LocalDateTime.of(2026, 7, 16, 10, 30, 45);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 16, 10, 31, 45);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private TransferService transferService;

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
    void createWithoutAuthenticationReturnsUnifiedUnauthorizedResponse() throws Exception {
        mockMvc.perform(post("/api/v1/transfers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(transferService);
    }

    @Test
    void createBindsRequestForwardsPrincipalAndReturnsTransferResponseSchema() throws Exception {
        when(transferService.create(eq(USER_ID), any(TransferRequest.class)))
                .thenReturn(transferResponse());

        MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("123.45")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(99))
                .andExpect(jsonPath("$.data.fromAccountId").value(FROM_ACCOUNT_ID))
                .andExpect(jsonPath("$.data.toAccountId").value(TO_ACCOUNT_ID))
                .andExpect(jsonPath("$.data.amount").value(123.45))
                .andExpect(jsonPath("$.data.currency").value("CNY"))
                .andExpect(jsonPath("$.data.description").value("monthly savings"))
                .andExpect(jsonPath("$.data.transactedAt").value("2026-07-16T10:30:45"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-07-16T10:31:45"))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
        assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("123.45");
        Set<String> responseFields = new HashSet<>();
        data.fieldNames().forEachRemaining(responseFields::add);
        assertThat(responseFields).containsExactlyInAnyOrder(
                "id", "fromAccountId", "toAccountId", "amount", "currency", "description", "transactedAt", "createdAt");

        ArgumentCaptor<TransferRequest> requestCaptor = ArgumentCaptor.forClass(TransferRequest.class);
        verify(transferService).create(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new TransferRequest(
                FROM_ACCOUNT_ID, TO_ACCOUNT_ID, new BigDecimal("123.45"), TRANSACTED_AT, "monthly savings"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "1.001", "10000000000000000.00"})
    void createRejectsInvalidAmountWithoutCallingService(String amount) throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson(amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(transferService);
    }

    @Test
    void createMapsSameAccountBusinessExceptionToBadRequest() throws Exception {
        when(transferService.create(eq(USER_ID), any(TransferRequest.class)))
                .thenThrow(new BusinessException(400, "转出账户与转入账户不能相同"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("123.45")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("转出账户与转入账户不能相同"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(transferService).create(eq(USER_ID), any(TransferRequest.class));
    }

    @Test
    void createMapsMissingOrForeignAccountBusinessExceptionToNotFound() throws Exception {
        when(transferService.create(eq(USER_ID), any(TransferRequest.class)))
                .thenThrow(new BusinessException(404, "账户不存在"));

        mockMvc.perform(post("/api/v1/transfers")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson("123.45")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("账户不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(transferService).create(eq(USER_ID), any(TransferRequest.class));
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }

    private String validRequestJson(String amount) {
        return """
                {"fromAccountId":7,"toAccountId":9,"amount":%s,"transactedAt":"2026-07-16T10:30:45","description":"monthly savings"}
                """.formatted(amount);
    }

    private TransferResponse transferResponse() {
        return new TransferResponse(99L, FROM_ACCOUNT_ID, TO_ACCOUNT_ID, new BigDecimal("123.45"), "CNY",
                "monthly savings", TRANSACTED_AT, CREATED_AT);
    }
}
