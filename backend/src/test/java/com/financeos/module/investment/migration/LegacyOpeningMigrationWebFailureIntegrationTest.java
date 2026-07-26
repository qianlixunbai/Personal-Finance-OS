package com.financeos.module.investment.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.integration.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "account.balance.lock-timeout=250ms")
class LegacyOpeningMigrationWebFailureIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LegacyAssetMigrationPreviewService previewService;

    @Autowired
    private DataSource dataSource;

    @MockBean
    private LegacyMigrationConsistencyChecker consistencyChecker;

    @Test
    void consistencyFailureRollsBackAndReturnsSanitizedHttp500() throws Exception {
        Fixture fixture = insertReadyLegacyAsset();
        String token = previewService.preview(fixture.userId(), fixture.assetId(), fixture.instrumentId(), fixture.accountId())
                .confirmation().previewToken();
        String forbidden = "SELECT secret token=" + token;
        doThrow(new LegacyMigrationConsistencyException(forbidden)).when(consistencyChecker)
                .verify(any(), any(), any(), any(), any(), any());

        String response = mockMvc.perform(post("/api/v1/investment/legacy-assets/{assetId}/migration-confirm", fixture.assetId())
                        .with(authentication(new UsernamePasswordAuthenticationToken(fixture.userId(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                        .header("X-Idempotency-Key", "consistency-failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("previewToken", token))))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("Migration consistency check failed")
                .doesNotContain("SELECT", "secret", token, "requestHash", "at com.financeos");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE asset_id = ?", Integer.class,
                fixture.assetId())).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT account_id, instrument_id, quantity, avg_cost, total_cost, realized_profit_loss,
                       position_status, position_mode, last_transaction_id, projection_version
                FROM assets WHERE id = ?
                """, fixture.assetId()))
                .containsEntry("account_id", null)
                .containsEntry("instrument_id", null)
                .containsEntry("quantity", new BigDecimal("3.00000000"))
                .containsEntry("avg_cost", new BigDecimal("10.00000000"))
                .containsEntry("total_cost", new BigDecimal("30.00"))
                .containsEntry("realized_profit_loss", new BigDecimal("0.00"))
                .containsEntry("position_status", "OPEN")
                .containsEntry("position_mode", "LEGACY")
                .containsEntry("last_transaction_id", null)
                .containsEntry("projection_version", 0);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class,
                fixture.accountId())).isEqualByComparingTo("0.00");
    }

    @Test
    void heldAccountLockReturnsSanitizedConflictWithoutPartialMigration() throws Exception {
        Fixture fixture = insertReadyLegacyAsset();
        String token = previewService.preview(fixture.userId(), fixture.assetId(), fixture.instrumentId(), fixture.accountId())
                .confirmation().previewToken();
        try (Connection heldLock = accountLock(fixture.accountId())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> response = executor.submit(() -> confirmViaWeb(fixture, token, "lock-timeout"));
                String body = response.get(5, TimeUnit.SECONDS);
                assertThat(body).contains("409").doesNotContain("55P03", "PostgreSQL", "accounts", "lock_timeout");
                assertNoPartialMigration(fixture);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void releasingAccountLockBeforeTimeoutAllowsMigration() throws Exception {
        Fixture fixture = insertReadyLegacyAsset();
        String token = previewService.preview(fixture.userId(), fixture.assetId(), fixture.instrumentId(), fixture.accountId())
                .confirmation().previewToken();
        try (Connection heldLock = accountLock(fixture.accountId())) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> response = executor.submit(() -> confirmViaWeb(fixture, token, "lock-release"));
                await().atMost(2, TimeUnit.SECONDS).until(() -> jdbcTemplate.queryForObject("""
                        SELECT count(*) > 0 FROM pg_stat_activity
                        WHERE wait_event_type = 'Lock' AND query LIKE '%FROM accounts%'
                        """, Boolean.class));
                heldLock.commit();
                assertThat(response.get(5, TimeUnit.SECONDS)).contains("\"code\":200");
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE asset_id = ?", Integer.class,
                        fixture.assetId())).isEqualTo(1);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private String confirmViaWeb(Fixture fixture, String token, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/investment/legacy-assets/{assetId}/migration-confirm", fixture.assetId())
                        .with(authentication(new UsernamePasswordAuthenticationToken(fixture.userId(), null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER")))))
                        .header("X-Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("previewToken", token))))
                .andReturn().getResponse().getContentAsString();
    }

    private Connection accountLock(Long accountId) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, accountId);
            statement.executeQuery();
        }
        return connection;
    }

    private void assertNoPartialMigration(Fixture fixture) {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE asset_id = ?", Integer.class,
                fixture.assetId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT position_mode FROM assets WHERE id = ?", String.class,
                fixture.assetId())).isEqualTo("LEGACY");
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class,
                fixture.accountId())).isEqualByComparingTo("0.00");
    }

    private Fixture insertReadyLegacyAsset() {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash) VALUES ('web-user', 'web-user@example.com', 'hash') RETURNING id
                """, Long.class);
        Long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0) RETURNING id
                """, Long.class, userId);
        Long instrumentId = jdbcTemplate.queryForObject("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'WEB', 'Web fund', 'FUND', 'FUND', 'CNY', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        Long assetId = jdbcTemplate.queryForObject("""
                INSERT INTO assets (user_id, name, symbol, type, market, currency, quantity, avg_cost, current_price,
                                    market_value, total_cost, realized_profit_loss, position_status, position_mode)
                VALUES (?, 'Web fund', 'WEB', 'FUND', 'FUND', 'CNY', 3, 10, 12, 36, 30, 0, 'OPEN', 'LEGACY') RETURNING id
                """, Long.class, userId);
        return new Fixture(userId, accountId, instrumentId, assetId);
    }

    private record Fixture(Long userId, Long accountId, Long instrumentId, Long assetId) {
    }
}
