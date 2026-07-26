package com.financeos.module.investment.migration;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.common.BusinessException;
import com.financeos.module.asset.service.AssetService;
import com.financeos.module.account.service.AccountService;
import com.financeos.module.investment.migration.dto.LegacyAssetMigrationConfirmResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyOpeningMigrationConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private LegacyAssetMigrationPreviewService previewService;

    @Autowired
    private LegacyAssetMigrationConfirmService confirmService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private AccountService accountService;

    @Test
    void concurrentSameKeyAndHashReturnsTheSameOpeningMigrationResult() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        String previewToken = previewService.preview(fixture.userId(), fixture.assetId(), fixture.instrumentId(), fixture.accountId())
                .confirmation().previewToken();
        CyclicBarrier startTogether = new CyclicBarrier(2);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LegacyAssetMigrationConfirmResponse> first = executor.submit(() -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return confirmService.confirm(fixture.userId(), fixture.assetId(), previewToken, "same-key");
            });
            Future<LegacyAssetMigrationConfirmResponse> second = executor.submit(() -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return confirmService.confirm(fixture.userId(), fixture.assetId(), previewToken, "same-key");
            });

            LegacyAssetMigrationConfirmResponse firstResult = first.get(10, TimeUnit.SECONDS);
            LegacyAssetMigrationConfirmResponse secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstResult.transactionId(), secondResult.transactionId())).hasSize(2).allMatch(id -> id != null);
            assertThat(firstResult.transactionId()).isEqualTo(secondResult.transactionId());
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM investment_transactions
                    WHERE user_id = ? AND asset_id = ? AND transaction_type = 'OPENING_POSITION' AND status = 'POSTED'
                    """, Integer.class, fixture.userId(), fixture.assetId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT position_mode FROM assets WHERE id = ?", String.class, fixture.assetId()))
                    .isEqualTo("TRANSACTION_DRIVEN");
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", java.math.BigDecimal.class, fixture.accountId()))
                    .isEqualByComparingTo("0.00");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentDifferentKeysForOneAssetCreatesOnlyOneOpening() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        String previewToken = previewToken(fixture);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> confirmAfterBarrier(startTogether, fixture, previewToken, "first-key"));
            Future<Outcome> second = executor.submit(() -> confirmAfterBarrier(startTogether, fixture, previewToken, "second-key"));

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .anySatisfy(Outcome::assertSuccess)
                    .anySatisfy(Outcome::assertConflict);
            assertSingleOpeningAndUnchangedBalance(fixture);
            assertThat(jdbcTemplate.queryForObject("SELECT projection_version FROM assets WHERE id = ?", Integer.class,
                    fixture.assetId())).isEqualTo(1);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void concurrentSameKeyWithDifferentValidPreviewHashesReturnsOneConflict() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        Long alternateAccountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Alternate', 'BROKERAGE', 'CNY', 0) RETURNING id
                """, Long.class, fixture.userId());
        String firstToken = previewToken(fixture);
        String secondToken = previewService.preview(fixture.userId(), fixture.assetId(), fixture.instrumentId(), alternateAccountId)
                .confirmation().previewToken();
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> first = executor.submit(() -> confirmAfterBarrier(startTogether, fixture, firstToken, "same-key"));
            Future<Outcome> second = executor.submit(() -> confirmAfterBarrier(startTogether, fixture, secondToken, "same-key"));
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .anySatisfy(Outcome::assertSuccess)
                    .anySatisfy(Outcome::assertConflict);
            assertSingleOpeningAndUnchangedBalance(fixture);
            assertThat(jdbcTemplate.queryForObject("SELECT account_id FROM assets WHERE id = ?", Long.class, fixture.assetId()))
                    .isIn(fixture.accountId(), alternateAccountId);
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void materialLegacyChangeAfterPreviewRejectsConfirmWithoutPartialMigration() {
        MigrationFixture fixture = insertReadyLegacyAsset();
        String token = previewToken(fixture);
        jdbcTemplate.update("UPDATE assets SET quantity = 4 WHERE id = ?", fixture.assetId());

        assertThatThrownBy(() -> confirmService.confirm(fixture.userId(), fixture.assetId(), token, "material-change"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(409);
        assertRolledBack(fixture);
    }

    @Test
    void concurrentMigrationAndCloseLeavesEitherAValidOpeningOrAClosedLegacyAsset() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        List<Outcome> outcomes = race(fixture, () -> assetService.close(fixture.userId(), fixture.assetId()));
        assertRaceIntegrity(fixture, outcomes);
    }

    @Test
    void concurrentMigrationAndDeleteCannotLeaveAnOrphanOpening() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        List<Outcome> outcomes = race(fixture, () -> assetService.delete(fixture.userId(), fixture.assetId()));
        assertRaceIntegrity(fixture, outcomes);
    }

    @Test
    void concurrentMigrationAndAccountDeactivateUsesTheAccountLockBoundary() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        List<Outcome> outcomes = race(fixture, () -> accountService.deactivate(fixture.userId(), fixture.accountId()));
        assertRaceIntegrity(fixture, outcomes);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, fixture.accountId()))
                .isEqualTo("INACTIVE");
    }

    @Test
    void concurrentMigrationAndInstrumentDeactivateUsesTheInstrumentRowLock() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        List<Outcome> outcomes = race(fixture, () -> jdbcTemplate.update(
                "UPDATE investment_instruments SET status = 'INACTIVE' WHERE id = ?", fixture.instrumentId()));
        assertRaceIntegrity(fixture, outcomes);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM investment_instruments WHERE id = ?", String.class,
                fixture.instrumentId())).isEqualTo("INACTIVE");
    }

    @Test
    void concurrentMigrationAndReferencePriceUpdatePreservesBothStates() throws Exception {
        MigrationFixture fixture = insertReadyLegacyAsset();
        String previewToken = previewToken(fixture);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LegacyAssetMigrationConfirmResponse> migration = executor.submit(() -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return confirmService.confirm(fixture.userId(), fixture.assetId(), previewToken, "price-race");
            });
            Future<?> price = executor.submit(() -> {
                startTogether.await(5, TimeUnit.SECONDS);
                return assetService.updatePrice(fixture.userId(), fixture.assetId(), new java.math.BigDecimal("15.00"));
            });

            assertThat(migration.get(10, TimeUnit.SECONDS).transactionId()).isNotNull();
            price.get(10, TimeUnit.SECONDS);
            assertSingleOpeningAndUnchangedBalance(fixture);
            assertThat(jdbcTemplate.queryForMap("""
                    SELECT account_id, instrument_id, position_mode, position_status, quantity, avg_cost, total_cost,
                           realized_profit_loss, last_transaction_id, projection_version, current_price, market_value
                    FROM assets WHERE id = ?
                    """, fixture.assetId()))
                    .containsEntry("account_id", fixture.accountId())
                    .containsEntry("instrument_id", fixture.instrumentId())
                    .containsEntry("position_mode", "TRANSACTION_DRIVEN")
                    .containsEntry("position_status", "OPEN")
                    .containsEntry("projection_version", 1)
                    .containsEntry("current_price", new java.math.BigDecimal("15.0000"))
                    .containsEntry("market_value", new java.math.BigDecimal("45.00"));
        } finally {
            shutdown(executor);
        }
    }

    @Test
    void bindingWriteFailureRollsBackTheEntireOpeningMigration() {
        MigrationFixture fixture = insertReadyLegacyAsset();
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_opening_binding() RETURNS trigger AS $$
                BEGIN
                  IF NEW.account_id IS NOT NULL AND OLD.account_id IS NULL THEN
                    RAISE EXCEPTION 'injected binding failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER fail_opening_binding BEFORE UPDATE ON assets
                FOR EACH ROW EXECUTE FUNCTION fail_opening_binding()
                """);
        try {
            assertThatThrownBy(() -> confirm(fixture, "binding-failure")).isInstanceOf(RuntimeException.class);
            assertRolledBack(fixture);
        } finally {
            dropFaultInjection("fail_opening_binding");
        }
    }

    @Test
    void openingInsertFailureRollsBackTheTemporaryBinding() {
        MigrationFixture fixture = insertReadyLegacyAsset();
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_opening_insert() RETURNS trigger AS $$
                BEGIN
                  RAISE EXCEPTION 'injected opening insert failure';
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER fail_opening_insert BEFORE INSERT ON investment_transactions
                FOR EACH ROW EXECUTE FUNCTION fail_opening_insert()
                """);
        try {
            assertThatThrownBy(() -> confirm(fixture, "opening-insert-failure")).isInstanceOf(RuntimeException.class);
            assertRolledBack(fixture);
        } finally {
            dropFaultInjection("fail_opening_insert");
        }
    }

    @Test
    void projectionWriteFailureAlsoRollsBackThePostedOpening() {
        MigrationFixture fixture = insertReadyLegacyAsset();
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_opening_projection() RETURNS trigger AS $$
                BEGIN
                  IF NEW.position_mode = 'TRANSACTION_DRIVEN' THEN
                    RAISE EXCEPTION 'injected projection failure';
                  END IF;
                  RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                CREATE TRIGGER fail_opening_projection BEFORE UPDATE ON assets
                FOR EACH ROW EXECUTE FUNCTION fail_opening_projection()
                """);
        try {
            assertThatThrownBy(() -> confirm(fixture, "projection-failure")).isInstanceOf(RuntimeException.class);
            assertRolledBack(fixture);
        } finally {
            dropFaultInjection("fail_opening_projection");
        }
    }

    private MigrationFixture insertReadyLegacyAsset() {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('migration-user', 'migration-user@example.com', 'hash') RETURNING id
                """, Long.class);
        Long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Brokerage', 'BROKERAGE', 'CNY', 0) RETURNING id
                """, Long.class, userId);
        Long instrumentId = jdbcTemplate.queryForObject("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'MIGRATE', 'Migration fund', 'FUND', 'FUND', 'CNY', 'ACTIVE') RETURNING id
                """, Long.class, userId);
        Long assetId = jdbcTemplate.queryForObject("""
                INSERT INTO assets (user_id, name, symbol, type, market, currency, quantity, avg_cost, current_price,
                                    market_value, total_cost, realized_profit_loss, position_status, position_mode)
                VALUES (?, 'Migration fund', 'MIGRATE', 'FUND', 'FUND', 'CNY', 3, 10, 12, 36, 30, 0, 'OPEN', 'LEGACY')
                RETURNING id
                """, Long.class, userId);
        return new MigrationFixture(userId, accountId, instrumentId, assetId);
    }

    private LegacyAssetMigrationConfirmResponse confirm(MigrationFixture fixture, String idempotencyKey) {
        return confirmService.confirm(fixture.userId(), fixture.assetId(), previewToken(fixture), idempotencyKey);
    }

    private String previewToken(MigrationFixture fixture) {
        return previewService.preview(fixture.userId(), fixture.assetId(), fixture.instrumentId(), fixture.accountId())
                .confirmation().previewToken();
    }

    private Outcome confirmAfterBarrier(CyclicBarrier barrier, MigrationFixture fixture, String token, String key) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            return Outcome.success(confirmService.confirm(fixture.userId(), fixture.assetId(), token, key));
        } catch (BusinessException exception) {
            return Outcome.failure(exception);
        }
    }

    private void assertSingleOpeningAndUnchangedBalance(MigrationFixture fixture) {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE asset_id = ?", Integer.class,
                fixture.assetId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", java.math.BigDecimal.class,
                fixture.accountId())).isEqualByComparingTo("0.00");
    }

    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private List<Outcome> race(MigrationFixture fixture, ThrowingRunnable competingOperation) throws Exception {
        String token = previewToken(fixture);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> migration = executor.submit(() -> confirmAfterBarrier(barrier, fixture, token, "race-key"));
            Future<Outcome> competing = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    competingOperation.run();
                    return Outcome.success(null);
                } catch (BusinessException exception) {
                    return Outcome.failure(exception);
                }
            });
            return List.of(migration.get(10, TimeUnit.SECONDS), competing.get(10, TimeUnit.SECONDS));
        } finally {
            shutdown(executor);
        }
    }

    private void assertRaceIntegrity(MigrationFixture fixture, List<Outcome> outcomes) {
        Integer openings = jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE asset_id = ?", Integer.class,
                fixture.assetId());
        assertThat(openings).isLessThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", java.math.BigDecimal.class,
                fixture.accountId())).isEqualByComparingTo("0.00");
        if (openings == 1) {
            assertThat(jdbcTemplate.queryForObject("SELECT position_mode FROM assets WHERE id = ?", String.class, fixture.assetId()))
                    .isEqualTo("TRANSACTION_DRIVEN");
        }
        assertThat(outcomes).isNotEmpty();
    }

    private void assertRolledBack(MigrationFixture fixture) {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE asset_id = ?", Integer.class,
                fixture.assetId())).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                SELECT account_id, instrument_id, position_mode, last_transaction_id, projection_version
                FROM assets WHERE id = ?
                """, fixture.assetId()))
                .containsEntry("account_id", null)
                .containsEntry("instrument_id", null)
                .containsEntry("position_mode", "LEGACY")
                .containsEntry("last_transaction_id", null)
                .containsEntry("projection_version", 0);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", java.math.BigDecimal.class,
                fixture.accountId())).isEqualByComparingTo("0.00");
    }

    private void dropFaultInjection(String name) {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + name + " ON "
                + (name.equals("fail_opening_insert") ? "investment_transactions" : "assets"));
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + name + "()");
    }

    private record MigrationFixture(Long userId, Long accountId, Long instrumentId, Long assetId) {
    }

    private record Outcome(LegacyAssetMigrationConfirmResponse response, BusinessException exception) {
        static Outcome success(LegacyAssetMigrationConfirmResponse response) {
            return new Outcome(response, null);
        }

        static Outcome failure(BusinessException exception) {
            return new Outcome(null, exception);
        }

        void assertSuccess() {
            assertThat(response).isNotNull();
            assertThat(exception).isNull();
        }

        void assertConflict() {
            assertThat(response).isNull();
            assertThat(exception).isNotNull();
            assertThat(exception.getCode()).isEqualTo(409);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

}
