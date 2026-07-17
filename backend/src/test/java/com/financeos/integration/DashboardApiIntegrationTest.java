package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.category.dto.CategoryRequest;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(DashboardApiIntegrationTest.FixedClockConfiguration.class)
class DashboardApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void realJwtDashboardContainsOnlyCurrentUsersMatchingDataAcrossAllAggregates() throws Exception {
        LoggedInUser userA = registerAndLogin("dashboard-a");
        LoggedInUser userB = registerAndLogin("dashboard-b");

        DashboardFixture fixtureA = createDashboardFixture(userA, "A-only");
        DashboardFixture fixtureB = createDashboardFixture(userB, "B-only");

        MvcResult result = mockMvc.perform(get("/api/v1/dashboard")
                        .header("Authorization", "Bearer " + userA.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.totalAssets").value(1200))
                .andExpect(jsonPath("$.data.netWorth").value(1200))
                .andExpect(jsonPath("$.data.monthIncome").value(1000))
                .andExpect(jsonPath("$.data.monthExpense").value(200))
                .andExpect(jsonPath("$.data.monthNet").value(800))
                .andExpect(jsonPath("$.data.assetAllocation.length()").value(1))
                .andExpect(jsonPath("$.data.assetAllocation[0].name").value("共享资产"))
                .andExpect(jsonPath("$.data.assetAllocation[0].value").value(100))
                .andExpect(jsonPath("$.data.assetAllocation[0].percentage").value(100))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend.length()").value(6))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[4].month").value("2026-02"))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[4].income").value(300))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[4].expense").value(0))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[5].month").value("2026-03"))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[5].income").value(1000))
                .andExpect(jsonPath("$.data.monthlyCashFlowTrend[5].expense").value(200))
                .andExpect(jsonPath("$.data.recentTransactions.length()").value(3))
                .andReturn();

        JsonNode dashboard = objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/data");
        JsonNode recentTransactions = dashboard.path("recentTransactions");
        assertThat(recentTransactions).extracting(transaction -> transaction.path("id").asLong())
                .containsExactly(fixtureA.marchExpenseId(), fixtureA.marchIncomeId(), fixtureA.februaryIncomeId());
        assertThat(recentTransactions).extracting(transaction -> transaction.path("id").asLong())
                .doesNotContain(fixtureB.februaryIncomeId(), fixtureB.marchIncomeId(), fixtureB.marchExpenseId());
        assertThat(recentTransactions).allSatisfy(transaction -> {
            assertThat(transaction.path("account").asText()).isEqualTo("共享账户");
            assertThat(transaction.path("category").asText()).startsWith("共享");
        });
    }

    private LoggedInUser registerAndLogin(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = prefix + suffix;
        String password = "password123";

        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(username, username + "@example.com", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        MvcResult login = mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();

        return new LoggedInUser(objectMapper.readTree(login.getResponse().getContentAsByteArray())
                .at("/data/token").asText());
    }

    private DashboardFixture createDashboardFixture(LoggedInUser user, String marker) throws Exception {
        Long accountId = createAccount(user.token());
        Long incomeCategoryId = createCategory(user.token(), "共享收入", "INCOME");
        Long expenseCategoryId = createCategory(user.token(), "共享支出", "EXPENSE");
        createAssetWithPrice(user.token());

        Long februaryIncomeId = createTransaction(user.token(), new TransactionRequest(
                accountId, incomeCategoryId, "INCOME", new BigDecimal("300.00"), "CNY",
                marker + "-feb-income", LocalDateTime.of(2026, 2, 10, 9, 0)));
        Long marchIncomeId = createTransaction(user.token(), new TransactionRequest(
                accountId, incomeCategoryId, "INCOME", new BigDecimal("1000.00"), "CNY",
                marker + "-mar-income", LocalDateTime.of(2026, 3, 10, 9, 0)));
        Long marchExpenseId = createTransaction(user.token(), new TransactionRequest(
                accountId, expenseCategoryId, "EXPENSE", new BigDecimal("200.00"), "CNY",
                marker + "-mar-expense", LocalDateTime.of(2026, 3, 12, 9, 0)));

        return new DashboardFixture(februaryIncomeId, marchIncomeId, marchExpenseId);
    }

    private Long createAccount(String token) throws Exception {
        return responseId(mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountRequest("共享账户", "CASH", "CNY"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());
    }

    private Long createCategory(String token, String name, String type) throws Exception {
        return responseId(mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CategoryRequest(name, type, null, 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());
    }

    private void createAssetWithPrice(String token) throws Exception {
        Long assetId = responseId(mockMvc.perform(post("/api/v1/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssetRequest(
                                "共享资产", "SHARED", "ETF", "CN", "CNY",
                                new BigDecimal("2.00000000"), new BigDecimal("40.0000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());

        mockMvc.perform(put("/api/v1/assets/{id}/price", assetId)
                        .header("Authorization", "Bearer " + token)
                        .param("price", "50.0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private Long createTransaction(String token, TransactionRequest request) throws Exception {
        return responseId(mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());
    }

    private Long responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/id").longValue();
    }

    private record LoggedInUser(String token) {
    }

    private record DashboardFixture(Long februaryIncomeId, Long marchIncomeId, Long marchExpenseId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-03-15T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }
}
