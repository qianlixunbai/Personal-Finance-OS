package com.financeos.module.investment;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.investment.command.InvestmentCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class InvestmentFirstBuyUnknownCommitIntegrationTest extends PostgresIntegrationTest {

    private static final String TRIGGER_NAME = "injected_first_buy_unknown_commit_rollback";
    private static final String FUNCTION_NAME = TRIGGER_NAME + "_fn";
    private static final String BODY = """
            {"accountId":1,"instrumentId":1,"quantity":"2.00000000",
             "unitPrice":"10.00000000","feeAmount":"1.00","taxAmount":"0.50"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @SpyBean
    private InvestmentCommandService commandService;

    @AfterEach
    void dropInjectedRollbackTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + TRIGGER_NAME + " ON investment_transactions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FUNCTION_NAME + "()");
    }

    @Test
    void committedUnknownFirstBuyReplaysItsReceiptAfterAccountBecomesInactive() throws Exception {
        Long userId = seedActiveBinding();
        hideCommittedResultOnce();

        assertThat(firstBuy(userId, "first-buy-unknown-account", BODY).status()).isEqualTo(500);
        assertThat(snapshotFromFreshConnection()).isEqualTo(committedSnapshot());
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");

        HttpResult recovered = firstBuy(userId, "first-buy-unknown-account", BODY);

        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.body()).contains("\"idempotentReplay\":true", "\"transactionType\":\"BUY\"",
                "\"balanceAfter\":\"-21.50\"");
        assertThat(snapshotFromFreshConnection()).isEqualTo(committedSnapshot());
    }

    @Test
    void committedUnknownFirstBuyReplaysItsReceiptAfterInstrumentBecomesInactive() throws Exception {
        Long userId = seedActiveBinding();
        hideCommittedResultOnce();

        assertThat(firstBuy(userId, "first-buy-unknown-instrument", BODY).status()).isEqualTo(500);
        assertThat(snapshotFromFreshConnection()).isEqualTo(committedSnapshot());
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");

        HttpResult recovered = firstBuy(userId, "first-buy-unknown-instrument", BODY);

        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.body()).contains("\"idempotentReplay\":true", "\"transactionId\":1");
        assertThat(snapshotFromFreshConnection()).isEqualTo(committedSnapshot());
    }

    @Test
    void sameKeyWithDifferentRequestConflictsBeforeAnInactiveAccountPrecondition() throws Exception {
        Long userId = seedActiveBinding();
        hideCommittedResultOnce();

        assertThat(firstBuy(userId, "first-buy-conflict", BODY).status()).isEqualTo(500);
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");

        HttpResult conflict = firstBuy(userId, "first-buy-conflict", BODY.replace("2.00000000", "3.00000000"));

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body()).contains("Idempotency-Key was already used with a different request")
                .doesNotContain("Inactive account or investment instrument cannot accept a buy");
        assertThat(snapshotFromFreshConnection()).isEqualTo(committedSnapshot());
    }

    @Test
    void committedUnknownFirstBuyReplaysItsReceiptAfterBothBindingsBecomeInactive() throws Exception {
        Long userId = seedActiveBinding();
        hideCommittedResultOnce();

        assertThat(firstBuy(userId, "first-buy-unknown-both", BODY).status()).isEqualTo(500);
        assertThat(snapshotFromFreshConnection()).isEqualTo(committedSnapshot());
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");

        HttpResult recovered = firstBuy(userId, "first-buy-unknown-both", BODY);

        assertThat(recovered.status()).isEqualTo(200);
        assertThat(recovered.body()).contains("\"idempotentReplay\":true", "\"transactionId\":1");
        assertThat(snapshotFromFreshConnection()).isEqualTo(committedSnapshot());
    }

    @Test
    void uncommittedUnknownFirstBuyIsStillRejectedWhenItsAccountBecomesInactive() throws Exception {
        Long userId = seedActiveBinding();
        hideFirstFailureAsUnknown();
        createRollbackTrigger("first-buy-uncommitted");

        assertThat(firstBuy(userId, "first-buy-uncommitted", BODY).status()).isEqualTo(500);
        assertThat(snapshotFromFreshConnection()).isEqualTo(emptySnapshot());
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");
        dropInjectedRollbackTrigger();

        HttpResult retry = firstBuy(userId, "first-buy-uncommitted", BODY);

        assertThat(retry.status()).isEqualTo(409);
        assertThat(retry.body()).doesNotContain("\"idempotentReplay\":true");
        assertThat(snapshotFromFreshConnection()).isEqualTo(emptySnapshot());
    }

    @Test
    void newFirstBuyKeysRemainSubjectToCurrentAccountAndInstrumentStatus() throws Exception {
        Long userId = seedActiveBinding();
        jdbcTemplate.update("UPDATE accounts SET status = 'INACTIVE' WHERE id = 1");

        assertThat(firstBuy(userId, "new-key-inactive-account", BODY).status()).isEqualTo(409);
        jdbcTemplate.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = 1");
        jdbcTemplate.update("UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = 1");

        assertThat(firstBuy(userId, "new-key-inactive-instrument", BODY).status()).isEqualTo(409);
        assertThat(snapshotFromFreshConnection()).isEqualTo(emptySnapshot());
    }

    private void hideCommittedResultOnce() {
        AtomicBoolean hideCommittedResultOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object response = invocation.callRealMethod();
            if (hideCommittedResultOnce.compareAndSet(true, false)) {
                throw new SimulatedUnknownCommitResult();
            }
            return response;
        }).when(commandService).firstBuy(anyLong(), anyString(), any());
    }

    private void hideFirstFailureAsUnknown() {
        AtomicBoolean hideFailureOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (RuntimeException failure) {
                if (hideFailureOnce.compareAndSet(true, false)) {
                    throw new SimulatedUnknownCommitResult();
                }
                throw failure;
            }
        }).when(commandService).firstBuy(anyLong(), anyString(), any());
    }

    private void createRollbackTrigger(String idempotencyKey) {
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.idempotency_key = '%s' THEN
                        RAISE EXCEPTION 'injected unknown commit rollback';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(FUNCTION_NAME, idempotencyKey));
        jdbcTemplate.execute("CREATE TRIGGER " + TRIGGER_NAME
                + " BEFORE INSERT ON investment_transactions"
                + " FOR EACH ROW EXECUTE FUNCTION " + FUNCTION_NAME + "()");
    }

    private Long seedActiveBinding() {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('first-buy-unknown', 'first-buy-unknown@example.com', 'hash') RETURNING id
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0)
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'FIRSTUNKNOWN', 'First Unknown Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """, userId);
        return userId;
    }

    private HttpResult firstBuy(Long userId, String key, String body) throws Exception {
        var response = mockMvc.perform(post("/api/v1/investment/positions")
                        .with(authentication(auth(userId)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
        return new HttpResult(response.getStatus(), response.getContentAsString());
    }

    private Snapshot snapshotFromFreshConnection() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                       (SELECT count(*) FROM investment_transactions) AS transactions,
                       (SELECT count(*) FROM assets) AS assets,
                       (SELECT balance FROM accounts WHERE id = 1) AS balance,
                       COALESCE((SELECT projection_version FROM assets WHERE id = 1), 0) AS projection_version,
                       (SELECT last_transaction_id FROM assets WHERE id = 1) AS last_transaction_id
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return new Snapshot(resultSet.getLong("transactions"), resultSet.getLong("assets"),
                    resultSet.getBigDecimal("balance"), resultSet.getInt("projection_version"),
                    resultSet.getObject("last_transaction_id", Long.class));
        }
    }

    private Snapshot committedSnapshot() {
        return new Snapshot(1, 1, new BigDecimal("-21.50"), 1, 1L);
    }

    private Snapshot emptySnapshot() {
        return new Snapshot(0, 0, new BigDecimal("0.00"), 0, null);
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private record HttpResult(int status, String body) {
    }

    private record Snapshot(long transactions, long assets, BigDecimal balance, int projectionVersion,
                            Long lastTransactionId) {
    }

    private static final class SimulatedUnknownCommitResult extends RuntimeException {
    }
}
