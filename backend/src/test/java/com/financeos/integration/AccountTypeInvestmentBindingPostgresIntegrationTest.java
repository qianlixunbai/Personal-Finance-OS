package com.financeos.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.investment.migration.LegacyAssetMigrationPreviewService;
import com.financeos.module.investment.migration.LegacyAssetMigrationConfirmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AccountTypeInvestmentBindingPostgresIntegrationTest extends PostgresIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private LegacyAssetMigrationPreviewService previewService;
    @Autowired private LegacyAssetMigrationConfirmService confirmService;

    private static final String CONFLICT = "账户类型与已有投资仓位绑定不兼容";

    @ParameterizedTest
    @ValueSource(strings = {"CASH", "OTHER"})
    void openPositionRejectsIncompatibleTypeAtomicallyAndKeepsLegalCommands(String targetType) throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        firstBuy().andExpect(status().isOk());
        Map<String, Object> before = snapshot();
        update(1L, 1L, targetType, "Must not persist")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(CONFLICT));
        assertThat(snapshot()).isEqualTo(before);
        command("/positions/1/sell", "sell", trade("1", "12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalPosition.quantity").value("1.00000000"));
        command("/positions/1/dividends", "dividend", Map.of("grossAmount", "3.00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balanceAfter").value("-5.00"));
        command("/positions/1/buy", "add", trade("1", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalPosition.quantity").value("2.00000000"));
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo("-15.00");
        assertThat(jdbcTemplate.queryForList("SELECT * FROM investment_transactions WHERE id = 1")).isEqualTo(before.get("facts"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reversal", "replacement"})
    void closedHistoryRejectsTypeChangeAndStillAllowsCorrectionsToReopen(String correction) throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        firstBuy().andExpect(status().isOk());
        command("/positions/1/sell", "close", trade("2", "12")).andExpect(status().isOk());
        Map<String, Object> before = snapshot();
        assertThat(jdbcTemplate.queryForObject("SELECT position_status FROM assets WHERE id = 1", String.class)).isEqualTo("CLOSED");
        for (String target : List.of("CASH", "OTHER")) {
            update(1L, 1L, target, "Must not persist").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(409)).andExpect(jsonPath("$.message").value(CONFLICT));
            assertThat(snapshot()).isEqualTo(before);
        }
        command("/positions/1/dividends", "dividend", Map.of("grossAmount", "3.00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.finalPosition.status").value("CLOSED"));
        command("/transactions/2/" + correction, "correct",
                correction.equals("reversal") ? Map.of("reason", "C3 correction") : replacement("1", "12"))
                .andExpect(status().isOk()).andExpect(jsonPath(correction.equals("reversal")
                        ? "$.data.positionStatus" : "$.data.finalPosition.status").value("OPEN"));
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM assets WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo(correction.equals("reversal") ? "2" : "1");
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class))
                .isEqualByComparingTo(correction.equals("reversal") ? "-17" : "-5");
        assertThat(jdbcTemplate.queryForList("SELECT * FROM investment_transactions WHERE id <= 2 ORDER BY id")).isEqualTo(before.get("facts"));
    }

    @Test
    void unchangedTypeAllowsMetadataUpdateEvenForInactivePositionBinding() throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        firstBuy().andExpect(status().isOk());
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");
        Map<String, Object> before = snapshot();
        update(1L, 1L, "BROKERAGE", "Renamed").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed"))
                .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.balance").value(-20.00));
        assertThat(snapshot().get("assets")).isEqualTo(before.get("assets"));
        assertThat(snapshot().get("facts")).isEqualTo(before.get("facts"));
        Map<String, Object> renamed = snapshot();
        update(1L, 1L, "CASH", "Must not persist").andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(renamed);
        command("/positions/1/dividends", "inactive-dividend", Map.of("grossAmount", "3.00")).andExpect(status().isOk());
    }

    @Test
    void accountWithoutPositionCanChangeTypeBothWaysAndRename() throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        update(1L, 1L, "CASH", "Cash").andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("CASH"));
        update(1L, 1L, "BROKERAGE", "Broker").andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("BROKERAGE"));
        update(1L, 1L, "BROKERAGE", "Renamed").andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Renamed"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM assets", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOCK", "ETF", "FUND", "BOND", "CRYPTO"})
    void compatibilityFollowsActualInstrumentClass(String assetClass) throws Exception {
        boolean crypto = assetClass.equals("CRYPTO");
        String originalType = crypto ? "CRYPTO_WALLET" : "BROKERAGE";
        seed(originalType, assetClass, crypto ? "CRYPTO" : "FUND");
        firstBuy().andExpect(status().isOk());
        Map<String, Object> before = snapshot();
        update(1L, 1L, crypto ? "BROKERAGE" : "CRYPTO_WALLET", "Must not persist").andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
        update(1L, 1L, originalType, "Compatible")
                .andExpect(status().isOk());
        command("/positions/1/sell", "sell", trade("1", "12")).andExpect(status().isOk());
    }

    @Test
    void otherUsersBindingDoesNotBlockUpdateAndCrossUserUpdateKeeps404() throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        firstBuy().andExpect(status().isOk());
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash) VALUES ('other', 'other@example.com', 'fixture-hash')");
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (2, 'Other', 'BROKERAGE', 'CNY', 0)");
        Map<String, Object> before = snapshot();
        update(2L, 1L, "CASH", "Foreign").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
        update(1L, 2L, "CASH", "Foreign").andExpect(status().isNotFound());
        assertThat(snapshot()).isEqualTo(before);
        update(2L, 2L, "CASH", "Allowed").andExpect(status().isOk());
    }

    @Test
    void sameUsersOtherAccountBindingDoesNotBlockAnEmptyAccount() throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        firstBuy().andExpect(status().isOk());
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Empty', 'BROKERAGE', 'CNY', 0)");
        Map<String, Object> boundAccount = jdbcTemplate.queryForMap("SELECT * FROM accounts WHERE id = 1");
        update(1L, 2L, "CASH", "Allowed").andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM accounts WHERE id = 1")).isEqualTo(boundAccount);
    }

    @Test
    void existingCurrencyAndDtoErrorsStillReturn400WithoutWrites() throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        firstBuy().andExpect(status().isOk());
        Map<String, Object> before = snapshot();
        mockMvc.perform(put("/api/v1/accounts/1").with(authentication(auth(1L))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Invalid\",\"type\":\"CASH\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        update(1L, 1L, "BROKERAGE", "").andExpect(status().isBadRequest());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void legacyAccountBindingCanStillChangeAndMigrationCanExplicitlyRemap() throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        seedLegacy(true);
        update(1L, 1L, "CASH", "Legacy cash").andExpect(status().isOk());
        var incompatiblePreview = previewService.preview(1L, 1L, 1L, 1L);
        assertThat(incompatiblePreview.confirmation()).isNull();
        assertThat(incompatiblePreview.validation().blockingErrors()).contains("INVALID_ACCOUNT_INSTRUMENT_BINDING");
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Target', 'BROKERAGE', 'CNY', 0)");
        String token = previewService.preview(1L, 1L, 1L, 2L).confirmation().previewToken();
        assertThat(token).isNotBlank();
        confirmService.confirm(1L, 1L, token, "remap");
        Map<String, Object> before = snapshot();
        update(1L, 2L, "CASH", "Must not persist").andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT account_id FROM assets WHERE id = 1", Long.class)).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentFirstBuyAndTypeChangeSerializeAtAccountRow(boolean investmentFirst) throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        Callable<Integer> buy = () -> firstBuy().andReturn().getResponse().getStatus();
        Callable<Integer> change = () -> update(1L, 1L, "CASH", "Changed").andReturn().getResponse().getStatus();
        List<Integer> result = interleave(investmentFirst ? buy : change, investmentFirst ? change : buy);
        assertThat(result).containsExactly(200, investmentFirst ? 409 : 400);
        if (investmentFirst) {
            assertBindingState("BROKERAGE", "2", "-20", 1, 1);
            assertThat(jdbcTemplate.queryForObject("SELECT name FROM accounts WHERE id = 1", String.class)).isEqualTo("Original");
        } else {
            assertThat(jdbcTemplate.queryForObject("SELECT type FROM accounts WHERE id = 1", String.class)).isEqualTo("CASH");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM assets", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"buy", "sell", "reversal", "replacement"})
    void concurrentExistingPositionWriteAndTypeChangePreserveBinding(String operation) throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        firstBuy().andExpect(status().isOk());
        Map<String, Object> original = jdbcTemplate.queryForMap("SELECT * FROM investment_transactions WHERE id = 1");
        Callable<Integer> write = () -> switch (operation) {
            case "buy", "sell" -> command("/positions/1/" + operation, "write", trade("1", "10")).andReturn().getResponse().getStatus();
            case "reversal" -> command("/transactions/1/reversal", "write", Map.of("reason", "C3 correction")).andReturn().getResponse().getStatus();
            default -> command("/transactions/1/replacement", "write", replacement("3", "10")).andReturn().getResponse().getStatus();
        };
        assertThat(interleave(write, () -> update(1L, 1L, "OTHER", "Must not persist").andReturn().getResponse().getStatus()))
                .containsExactly(200, 409);
        switch (operation) {
            case "buy", "replacement" -> assertBindingState("BROKERAGE", "3", "-30", 2, operation.equals("buy") ? 2 : 3);
            case "sell" -> assertBindingState("BROKERAGE", "1", "-10", 2, 2);
            default -> assertBindingState("BROKERAGE", "0", "0", 2, 2);
        }
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM investment_transactions WHERE id = 1")).isEqualTo(original);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM accounts WHERE id = 1", String.class)).isEqualTo("Original");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transaction_corrections", Integer.class))
                .isEqualTo(operation.equals("replacement") ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentOpeningMigrationAndTypeChangeRevalidateUnderAccountLock(boolean migrationFirst) throws Exception {
        seed("BROKERAGE", "FUND", "FUND");
        seedLegacy(false);
        String token = previewService.preview(1L, 1L, 1L, 1L).confirmation().previewToken();
        assertThat(token).isNotBlank();
        Map<String, Object> legacy = jdbcTemplate.queryForMap("SELECT * FROM assets WHERE id = 1");
        Callable<Integer> migrate = () -> mockMvc.perform(post("/api/v1/investment/legacy-assets/1/migration-confirm").with(authentication(auth(1L)))
                .header("X-Idempotency-Key", "opening").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("previewToken", token)))).andReturn().getResponse().getStatus();
        Callable<Integer> change = () -> update(1L, 1L, "CASH", "Changed").andReturn().getResponse().getStatus();
        assertThat(interleave(migrationFirst ? migrate : change, migrationFirst ? change : migrate)).containsExactly(200, 409);
        if (migrationFirst) {
            assertBindingState("BROKERAGE", "2", "0", 1, 1);
            assertThat(jdbcTemplate.queryForObject("SELECT position_mode FROM assets WHERE id = 1", String.class)).isEqualTo("TRANSACTION_DRIVEN");
        } else {
            assertThat(jdbcTemplate.queryForMap("SELECT * FROM assets WHERE id = 1")).isEqualTo(legacy);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT type FROM accounts WHERE id = 1", String.class)).isEqualTo("CASH");
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isZero();
        }
    }

    private void seedLegacy(boolean accountBound) {
        jdbcTemplate.update("""
                INSERT INTO assets (user_id, account_id, name, symbol, type, market, currency, quantity, avg_cost,
                                    total_cost, realized_profit_loss, position_status, position_mode)
                VALUES (1, ?, 'C3 Instrument', 'C3', 'FUND', 'FUND', 'CNY', 2, 10, 20, 0, 'OPEN', 'LEGACY')
                """, accountBound ? 1L : null);
    }

    // Hold the successful real service operation's transaction open, then prove the second
    // operation is waiting on that PostgreSQL Account lock before allowing the holder to commit.
    private List<Integer> interleave(Callable<Integer> holder, Callable<Integer> waiter) throws Exception {
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                holderPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                try {
                    int outcome = holder.call();
                    assertThat(outcome).as("holder must complete a real successful write").isEqualTo(200);
                    holderReady.countDown();
                    assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    return outcome;
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }));
            assertThat(holderReady.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = executor.submit(() -> {
                waiterStarted.countDown();
                return waiter.call();
            });
            assertThat(waiterStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitAccountLockWait(holderPid.get());
            assertThat(second.isDone()).isFalse();
            release.countDown();
            List<Integer> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Integer.class)).isZero();
            return outcomes;
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitAccountLockWait(int holderPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                      AND query ILIKE '%FROM accounts%FOR UPDATE%'
                      AND ?::int = ANY(pg_blocking_pids(pid))
                    """, Integer.class, holderPid);
            if (count != null && count > 0) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Second operation did not wait on the holder's PostgreSQL Account lock");
    }

    private void assertBindingState(String type, String quantity, String balance, int version, int facts) {
        assertThat(jdbcTemplate.queryForObject("SELECT type FROM accounts WHERE id = 1", String.class)).isEqualTo(type);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = 1", BigDecimal.class)).isEqualByComparingTo(balance);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM assets WHERE id = 1", BigDecimal.class)).isEqualByComparingTo(quantity);
        assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = 1", Integer.class)).isEqualTo(version);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions", Integer.class)).isEqualTo(facts);
    }

    private void seed(String accountType, String assetClass, String market) {
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash) VALUES ('c3-user', 'c3@example.com', 'fixture-hash')");
        jdbcTemplate.update("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Original', ?, 'CNY', 0)", accountType);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (1, 'C3', 'C3 Instrument', ?, ?, 'CNY', 'ACTIVE')
                """, market, assetClass);
    }

    private ResultActions firstBuy() throws Exception {
        return command("/positions", "first", Map.of("accountId", 1, "instrumentId", 1,
                "quantity", "2.00000000", "unitPrice", "10.00000000", "feeAmount", "0.00", "taxAmount", "0.00"));
    }

    private ResultActions command(String path, String key, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/investment" + path).with(authentication(auth(1L)))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions update(Long userId, Long accountId, String type, String name) throws Exception {
        return mockMvc.perform(put("/api/v1/accounts/{id}", accountId).with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name, "type", type, "currency", "CNY"))));
    }

    private Map<String, Object> trade(String quantity, String price) {
        return Map.of("quantity", quantity + ".00000000", "unitPrice", price + ".00000000", "feeAmount", "0.00", "taxAmount", "0.00");
    }

    private Map<String, Object> replacement(String quantity, String price) {
        return Map.of("quantity", quantity + ".00000000", "unitPrice", price + ".00000000", "feeAmount", "0.00", "taxAmount", "0.00", "reason", "C3 correction");
    }

    private Map<String, Object> snapshot() {
        return Map.of("accounts", jdbcTemplate.queryForList("SELECT * FROM accounts ORDER BY id"),
                "assets", jdbcTemplate.queryForList("SELECT * FROM assets ORDER BY id"),
                "facts", jdbcTemplate.queryForList("SELECT * FROM investment_transactions ORDER BY id"),
                "corrections", jdbcTemplate.queryForList("SELECT * FROM investment_transaction_corrections ORDER BY id"));
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
