package com.financeos.module.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.dashboard.service.DashboardService;
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
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class DashboardControllerWebMvcTest {

    private static final Long USER_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private DashboardService dashboardService;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain filterChain = invocation.getArgument(2);
            filterChain.doFilter(request, response);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void dashboardReturnsTypedSuccessResponseForLongPrincipal() throws Exception {
        when(dashboardService.getDashboard(USER_ID)).thenReturn(dashboard());

        MvcResult result = mockMvc.perform(get("/api/v1/dashboard").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.data.totalAssets").isNumber())
                .andExpect(jsonPath("$.data.netWorth").isNumber())
                .andExpect(jsonPath("$.data.monthIncome").isNumber())
                .andExpect(jsonPath("$.data.monthExpense").isNumber())
                .andExpect(jsonPath("$.data.monthNet").isNumber())
                .andExpect(jsonPath("$.data.assetAllocation").isArray())
                .andExpect(jsonPath("$.data.assetAllocation[0].value").isNumber())
                .andExpect(jsonPath("$.data.assetAllocation[0].percentage").isNumber())
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend").isArray())
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend.length()").value(6))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[0].month").value("2026-02"))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[0].income").isNumber())
                .andExpect(jsonPath("$.data.recentTransactions").isArray())
                .andExpect(jsonPath("$.data.recentTransactions[0].id").isNumber())
                .andExpect(jsonPath("$.data.recentTransactions[0].amount").isNumber())
                .andExpect(jsonPath("$.data.recentTransactions[0].date").value("2026-07-16T10:30:45"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertDecimal(json, "/data/totalAssets", "2450.75");
        assertDecimal(json, "/data/netWorth", "2450.75");
        assertDecimal(json, "/data/monthIncome", "980.10");
        assertDecimal(json, "/data/monthExpense", "321.55");
        assertDecimal(json, "/data/monthNet", "658.55");
        assertDecimal(json, "/data/assetAllocation/0/value", "450.75");
        assertDecimal(json, "/data/assetAllocation/0/percentage", "100.0000");
        assertDecimal(json, "/data/monthlyCashFlowTrend/0/income", "500.00");
        assertDecimal(json, "/data/monthlyCashFlowTrend/0/expense", "100.00");
        assertDecimal(json, "/data/monthlyCashFlowTrend/0/net", "400.00");
        assertDecimal(json, "/data/recentTransactions/0/amount", "88.50");
        verify(dashboardService).getDashboard(USER_ID);
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }

    private DashboardDto dashboard() {
        return new DashboardDto(
                new BigDecimal("2450.75"),
                new BigDecimal("2450.75"),
                new BigDecimal("980.10"),
                new BigDecimal("321.55"),
                new BigDecimal("658.55"),
                List.of(new DashboardDto.AssetAllocation("CSI 300 ETF", new BigDecimal("450.75"), new BigDecimal("100.0000"))),
                List.of(
                        cashFlow("2026-02", "500.00", "100.00", "400.00"),
                        cashFlow("2026-03", "0.00", "0.00", "0.00"),
                        cashFlow("2026-04", "200.00", "80.00", "120.00"),
                        cashFlow("2026-05", "0.00", "50.00", "-50.00"),
                        cashFlow("2026-06", "280.10", "91.55", "188.55"),
                        cashFlow("2026-07", "980.10", "321.55", "658.55")
                ),
                List.of(new DashboardDto.RecentTransaction(
                        7L, "EXPENSE", new BigDecimal("88.50"), "Groceries", "Cash", "2026-07-16T10:30:45"))
        );
    }

    private DashboardDto.MonthlyCashFlow cashFlow(String month, String income, String expense, String net) {
        return new DashboardDto.MonthlyCashFlow(
                month, new BigDecimal(income), new BigDecimal(expense), new BigDecimal(net));
    }

    private void assertDecimal(JsonNode json, String pointer, String expected) {
        assertThat(json.at(pointer).decimalValue()).isEqualByComparingTo(expected);
    }
}
