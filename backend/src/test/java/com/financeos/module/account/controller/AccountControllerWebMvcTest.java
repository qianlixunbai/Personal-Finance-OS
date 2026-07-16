package com.financeos.module.account.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.common.PageResult;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.account.dto.AccountResponse;
import com.financeos.module.account.service.AccountService;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class})
class AccountControllerWebMvcTest {

    private static final Long USER_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private AccountService accountService;

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
    void listWithoutAuthenticationReturnsUnifiedUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(accountService);
    }

    @Test
    void listReturnsAccountsForLongPrincipalWithNumericBalanceAndIsoTimestamp() throws Exception {
        AccountResponse response = accountResponse(7L, "现金账户", new BigDecimal("123.45"));
        when(accountService.listByUser(USER_ID)).thenReturn(List.of(response));

        MvcResult result = mockMvc.perform(get("/api/v1/accounts").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").isNumber())
                .andExpect(jsonPath("$.data[0].name").value("现金账户"))
                .andExpect(jsonPath("$.data[0].balance").isNumber())
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-07-16T10:30:45"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/0/balance").decimalValue()).isEqualByComparingTo("123.45");
        verify(accountService).listByUser(USER_ID);
    }

    @Test
    void pageUsesDefaultParametersAndReturnsPageStructure() throws Exception {
        PageResult<AccountResponse> page = new PageResult<>(
                List.of(accountResponse(7L, "现金账户", new BigDecimal("123.45"))), 1L, 1, 20);
        when(accountService.pageByUser(USER_ID, 1, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/accounts/page").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").isNumber())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").isNumber())
                .andExpect(jsonPath("$.data.size").value(20));

        verify(accountService).pageByUser(USER_ID, 1, 20);
    }

    @Test
    void createReturnsAccountAndForwardsValidatedRequestForLongPrincipal() throws Exception {
        AccountResponse response = accountResponse(7L, "现金账户", new BigDecimal("0.00"));
        when(accountService.create(eq(USER_ID), any(AccountRequest.class))).thenReturn(response);

        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"现金账户","type":"CASH","currency":"CNY"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.name").value("现金账户"))
                .andExpect(jsonPath("$.data.balance").isNumber())
                .andExpect(jsonPath("$.data.createdAt").value("2026-07-16T10:30:45"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/balance").decimalValue()).isEqualByComparingTo("0.00");
        ArgumentCaptor<AccountRequest> requestCaptor = ArgumentCaptor.forClass(AccountRequest.class);
        verify(accountService).create(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new AccountRequest("现金账户", "CASH", "CNY"));
    }

    @Test
    void createRejectsBlankNameWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","type":"CASH","currency":"CNY"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(accountService);
    }

    @Test
    void getMapsMissingOrForeignAccountToNotFoundForCurrentUser() throws Exception {
        when(accountService.getById(USER_ID, 99L))
                .thenThrow(new BusinessException(404, "账户不存在"));

        mockMvc.perform(get("/api/v1/accounts/{id}", 99L).with(authentication(currentUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("账户不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(accountService).getById(USER_ID, 99L);
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }

    private AccountResponse accountResponse(Long id, String name, BigDecimal balance) {
        return new AccountResponse(
                id,
                name,
                "CASH",
                "CNY",
                balance,
                "ACTIVE",
                LocalDateTime.of(2026, 7, 16, 10, 30, 45)
        );
    }
}
