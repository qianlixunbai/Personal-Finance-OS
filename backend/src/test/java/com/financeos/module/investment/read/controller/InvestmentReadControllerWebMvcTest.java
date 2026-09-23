package com.financeos.module.investment.read.controller;

import com.financeos.common.BusinessException;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.investment.read.dto.InvestmentPortfolioResponse;
import com.financeos.module.investment.read.service.InvestmentLogicalTransactionDetailQueryService;
import com.financeos.module.investment.read.service.InvestmentLogicalTransactionReadQueryService;
import com.financeos.module.investment.read.service.InvestmentPortfolioQueryService;
import com.financeos.module.investment.read.service.InvestmentPositionDetailQueryService;
import com.financeos.module.investment.read.service.InvestmentPositionReadQueryService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InvestmentReadController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class InvestmentReadControllerWebMvcTest {
    private static final Long USER_ID = 42L;

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtAuthFilter jwtAuthFilter;
    @MockBean private InvestmentPortfolioQueryService portfolioService;
    @MockBean private InvestmentPositionReadQueryService positionService;
    @MockBean private InvestmentPositionDetailQueryService detailService;
    @MockBean private InvestmentLogicalTransactionReadQueryService transactionService;
    @MockBean private InvestmentLogicalTransactionDetailQueryService transactionDetailService;

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
    void portfolioUsesThePrincipalAndSerializesFinancialValuesAsStrings() throws Exception {
        when(portfolioService.get(USER_ID)).thenReturn(new InvestmentPortfolioResponse("CNY", 0, 0, 0, "0.00", "0.00",
                new InvestmentPortfolioResponse.PortfolioReferenceValuation("CNY", null, 0, 0, "UNAVAILABLE", List.of())));

        mockMvc.perform(get("/api/v1/investment/portfolio").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.openTotalCost").value("0.00"))
                .andExpect(jsonPath("$.data.referenceValuation").exists())
                .andExpect(jsonPath("$.data.referenceValuation.value").doesNotExist());

        verify(portfolioService).get(USER_ID);
    }

    @Test
    void logicalTransactionDetailAndAuditTimelineUseOnlyTheLogicalId() throws Exception {
        mockMvc.perform(get("/api/v1/investment/transactions/9").with(authentication(currentUser())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/investment/transactions/9/audit-timeline").with(authentication(currentUser())))
                .andExpect(status().isOk());

        verify(transactionDetailService).get(USER_ID, 9L);
        verify(transactionDetailService).auditTimeline(USER_ID, 9L);
    }

    @Test
    void invalidTransactionInputReturns400BeforeCallingReadServices() throws Exception {
        mockMvc.perform(get("/api/v1/investment/transactions").param("size", "0")
                        .with(authentication(currentUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/v1/investment/transactions/0").with(authentication(currentUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mockMvc.perform(get("/api/v1/investment/transactions/0/audit-timeline").with(authentication(currentUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(transactionService, transactionDetailService);
    }

    @Test
    void missingPositionReturns404WithoutLeakingOwnershipReason() throws Exception {
        when(detailService.get(USER_ID, 99L)).thenThrow(new BusinessException(404, "Investment position not found"));

        mockMvc.perform(get("/api/v1/investment/positions/99").with(authentication(currentUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("Investment position not found"));
    }

    @Test
    void unauthenticatedInvestmentReadReturns401WithoutCallingServices() throws Exception {
        for (String path : List.of("/api/v1/investment/positions", "/api/v1/investment/transactions",
                "/api/v1/investment/transactions/9", "/api/v1/investment/transactions/9/audit-timeline")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }

        verifyNoInteractions(portfolioService, positionService, detailService, transactionService, transactionDetailService);
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }
}
