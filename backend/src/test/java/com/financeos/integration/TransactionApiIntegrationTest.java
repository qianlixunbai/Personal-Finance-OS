package com.financeos.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.category.dto.CategoryRequest;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "account.balance.lock-timeout=750ms")
class TransactionApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Test
    void realHttpCreateUpdateAndDeleteRestoreAccountBalanceAndRemoveTransaction() throws Exception {
        LoggedInUser user = registerAndLogin("balance");
        Long accountId = createAccount(user.token(), "balance-account-" + UUID.randomUUID());
        Long incomeCategoryId = createCategory(user.token(), "inc-" + UUID.randomUUID(), "INCOME");
        Long expenseCategoryId = createCategory(user.token(), "exp-" + UUID.randomUUID(), "EXPENSE");

        Long transactionId = createTransaction(user.token(), new TransactionRequest(
                accountId, incomeCategoryId, "INCOME", new BigDecimal("100.00"), "CNY",
                "income-" + UUID.randomUUID(), LocalDateTime.of(2026, 7, 10, 9, 0)));
        assertAccountBalance(user.token(), accountId, "100.00");

        updateTransaction(user.token(), transactionId, new TransactionRequest(
                accountId, expenseCategoryId, "EXPENSE", new BigDecimal("40.00"), "CNY",
                "expense-" + UUID.randomUUID(), LocalDateTime.of(2026, 7, 11, 9, 0)));
        assertAccountBalance(user.token(), accountId, "-40.00");

        mockMvc.perform(delete("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertAccountBalance(user.token(), accountId, "0.00");

        mockMvc.perform(get("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("流水不存在"));
    }

    @Test
    void realHttpDoesNotExposeAnotherUsersTransactionsInPageOrDetail() throws Exception {
        LoggedInUser userA = registerAndLogin("isolation-a");
        LoggedInUser userB = registerAndLogin("isolation-b");
        Long accountId = createAccount(userA.token(), "isolation-account-" + UUID.randomUUID());
        Long categoryId = createCategory(userA.token(), "cat-" + UUID.randomUUID(), "INCOME");
        Long transactionId = createTransaction(userA.token(), new TransactionRequest(
                accountId, categoryId, "INCOME", new BigDecimal("100.00"), "CNY",
                "isolation-" + UUID.randomUUID(), LocalDateTime.of(2026, 7, 12, 9, 0)));

        MvcResult page = mockMvc.perform(get("/api/v1/transactions/page")
                        .header("Authorization", "Bearer " + userB.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records").isEmpty())
                .andReturn();
        assertThat(objectMapper.readTree(page.getResponse().getContentAsByteArray()).at("/data/total").asInt()).isZero();

        mockMvc.perform(get("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", "Bearer " + userB.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("流水不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void realHttpPageAppliesCombinedFiltersWithInclusiveEndTime() throws Exception {
        LoggedInUser user = registerAndLogin("filter");
        Long accountId = createAccount(user.token(), "filter-account-" + UUID.randomUUID());
        Long incomeCategoryId = createCategory(user.token(), "inc-" + UUID.randomUUID(), "INCOME");
        Long expenseCategoryId = createCategory(user.token(), "exp-" + UUID.randomUUID(), "EXPENSE");
        String matchingDescription = "matching-" + UUID.randomUUID();
        String excludedDescription = "excluded-" + UUID.randomUUID();

        createTransaction(user.token(), new TransactionRequest(
                accountId, incomeCategoryId, "INCOME", new BigDecimal("123.45"), "CNY",
                matchingDescription, LocalDateTime.of(2026, 7, 31, 23, 59, 59)));
        createTransaction(user.token(), new TransactionRequest(
                accountId, expenseCategoryId, "EXPENSE", new BigDecimal("40.00"), "CNY",
                excludedDescription, LocalDateTime.of(2026, 7, 20, 12, 0)));

        MvcResult result = mockMvc.perform(get("/api/v1/transactions/page")
                        .header("Authorization", "Bearer " + user.token())
                        .param("accountId", accountId.toString())
                        .param("categoryId", incomeCategoryId.toString())
                        .param("type", "INCOME")
                        .param("start", "2026-07-01T00:00:00")
                        .param("end", "2026-07-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].description").value(matchingDescription))
                .andExpect(jsonPath("$.data.records[0].transactedAt").value("2026-07-31T23:59:59"))
                .andReturn();

        JsonNode records = objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/records");
        assertThat(records.get(0).path("amount").decimalValue()).isEqualByComparingTo("123.45");
        assertThat(records.toString()).doesNotContain(excludedDescription);
    }

    @Test
    void lockTimeout_returnsHttp409WithoutPartialCommit() throws Exception {
        LoggedInUser user = registerAndLogin("lock-timeout");
        Long accountId = createAccount(user.token(), "lock-timeout-account-" + UUID.randomUUID());
        Long categoryId = createCategory(user.token(), "lt-cat-" + UUID.randomUUID(), "INCOME");
        Long userId = jdbcTemplate.queryForObject("SELECT user_id FROM accounts WHERE id = ?", Long.class, accountId);
        Connection heldLock = lockAccountRow(accountId, userId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> request = executor.submit(() -> mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", "Bearer " + user.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new TransactionRequest(
                                    accountId, categoryId, "INCOME", new BigDecimal("20.00"), "CNY",
                                    "lock timeout", LocalDateTime.of(2026, 7, 23, 13, 0)))))
                    .andReturn());

            MvcResult result = request.get(5, TimeUnit.SECONDS);
            JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(response.path("code").asInt()).isEqualTo(409);
            assertThat(response.path("message").asText()).isEqualTo("并发操作冲突，请重试");
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("55P03", "PostgreSQL", "accounts", "SELECT");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ?", Integer.class, accountId))
                    .isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId))
                    .isEqualByComparingTo("0.00");
        } finally {
            rollbackAndClose(heldLock);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void lockReleasedBeforeTimeout_allowsRequestToSucceed() throws Exception {
        LoggedInUser user = registerAndLogin("lock-release");
        Long accountId = createAccount(user.token(), "lock-release-account-" + UUID.randomUUID());
        Long categoryId = createCategory(user.token(), "lr-cat-" + UUID.randomUUID(), "INCOME");
        Long userId = jdbcTemplate.queryForObject("SELECT user_id FROM accounts WHERE id = ?", Long.class, accountId);
        Connection heldLock = lockAccountRow(accountId, userId);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> request = executor.submit(() -> mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", "Bearer " + user.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new TransactionRequest(
                                    accountId, categoryId, "INCOME", new BigDecimal("20.00"), "CNY",
                                    "lock release", LocalDateTime.of(2026, 7, 23, 13, 0)))))
                    .andReturn());

            awaitHttpRequestLockWait();
            heldLock.commit();
            heldLock.close();
            heldLock = null;

            MvcResult result = request.get(5, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("code").asInt()).isEqualTo(200);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ?", Integer.class, accountId))
                    .isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId))
                    .isEqualByComparingTo("20.00");
        } finally {
            rollbackAndClose(heldLock);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
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
        return new LoggedInUser(objectMapper.readTree(login.getResponse().getContentAsByteArray()).at("/data/token").asText());
    }

    private Connection lockAccountRow(Long accountId, Long userId) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM accounts WHERE id = ? AND user_id = ? FOR UPDATE
                """)) {
            statement.setLong(1, accountId);
            statement.setLong(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
        return connection;
    }

    private void awaitHttpRequestLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("HTTP request did not enter a PostgreSQL lock wait");
    }

    private void rollbackAndClose(Connection connection) throws Exception {
        if (connection == null || connection.isClosed()) {
            return;
        }
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }

    private Long createAccount(String token, String name) throws Exception {
        return responseId(mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountRequest(name, "CASH", "CNY"))))
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

    private Long createTransaction(String token, TransactionRequest request) throws Exception {
        return responseId(mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn());
    }

    private void updateTransaction(String token, Long transactionId, TransactionRequest request) throws Exception {
        mockMvc.perform(put("/api/v1/transactions/{id}", transactionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void assertAccountBalance(String token, Long accountId, String expected) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").isNumber())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(response.at("/data/balance").decimalValue()).isEqualByComparingTo(expected);
    }

    private Long responseId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/data/id").longValue();
    }

    private record LoggedInUser(String token) {
    }
}
