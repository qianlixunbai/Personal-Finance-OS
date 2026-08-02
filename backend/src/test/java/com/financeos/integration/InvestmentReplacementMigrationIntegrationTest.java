package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class InvestmentReplacementMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeEach
    void migrateThroughV12() {
        flyway("12").clean();
        flyway("12").migrate();
    }

    @Test
    void upgradesV12ToV13WithReplacementSchemaAndCompatibleFactDefaults() throws SQLException {
        insertV12Buy();

        migrateThroughV13();

        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success")).isEqualTo(1);
        assertThat(queryForInt("SELECT count(*) FROM investment_transaction_corrections")).isZero();
        assertThat(queryForInt("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'investment_transactions'
                  AND column_name IN ('correction_group_id', 'replay_anchor_transaction_id', 'replay_sequence')
                """)).isEqualTo(3);
        assertThat(queryForInt("""
                SELECT count(*) FROM investment_transactions
                WHERE correction_group_id IS NULL
                  AND replay_anchor_transaction_id IS NULL
                  AND replay_sequence = 0
                """)).isEqualTo(1);
        assertThat(queryForInt("""
                SELECT count(*) FROM pg_trigger
                WHERE tgrelid = 'investment_transactions'::regclass
                  AND tgname = 'trg_validate_investment_replacement_fact'
                """)).isEqualTo(1);
    }

    @Test
    void migratesACleanV12BaselineWithoutSchemaQualificationFalsePositives() throws SQLException {
        migrateThroughV13();

        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success")).isEqualTo(1);
        assertThat(queryForInt("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name = 'investment_transaction_corrections'")).isEqualTo(1);
    }

    @Test
    void preservesStandaloneV12ReversalReceiptsAndReplayDefaults() throws SQLException {
        insertV12Buy();
        insertV12StandaloneReversal();

        migrateThroughV13();

        assertThat(queryForInt("""
                SELECT count(*) FROM investment_transactions
                WHERE transaction_type = 'REVERSAL'
                  AND correction_group_id IS NULL
                  AND replay_anchor_transaction_id IS NULL
                  AND replay_sequence = 0
                  AND account_balance_after IS NOT NULL
                  AND position_quantity_after IS NOT NULL
                  AND position_avg_cost_after IS NOT NULL
                  AND position_total_cost_after IS NOT NULL
                  AND position_realized_profit_loss_after IS NOT NULL
                  AND position_status_after IS NOT NULL
                  AND projection_version_after IS NOT NULL
                """)).isEqualTo(1);
    }

    @Test
    void rejectsUnexpectedV12BaselineBeforeApplyingAnyV13Ddl() throws SQLException {
        execute("ALTER TABLE investment_transactions DROP CONSTRAINT ck_investment_transactions_receipt");

        try (Connection connection = connection()) {
            assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(this::migrateThroughV13))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("expected V12 receipt constraint is missing");
            assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '13'")).isZero();
            assertThat(queryForInt("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_name = 'investment_transaction_corrections'
                    """)).isZero();
        }
    }

    @Test
    void rejectsSameNamedV12ReceiptConstraintDriftBeforeDroppingIt() throws SQLException {
        execute("ALTER TABLE investment_transactions DROP CONSTRAINT ck_investment_transactions_receipt");
        execute("ALTER TABLE investment_transactions ADD CONSTRAINT ck_investment_transactions_receipt CHECK (true)");

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(this::migrateThroughV13))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("expected V12 receipt constraint definition is incompatible");
        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '13'")).isZero();
        assertThat(queryForInt("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name = 'investment_transaction_corrections'")).isZero();
    }

    @Test
    void rejectsV12ColumnDefinitionDriftBeforeApplyingAnyV13Ddl() throws SQLException {
        execute("ALTER TABLE investment_transactions ALTER COLUMN status TYPE VARCHAR(21)");

        assertV13FailsBeforeAnySchemaChange("expected V12 column definition is incompatible: status");
    }

    @Test
    void rejectsSameNamedV12CorrectionConstraintAndOriginalBindingDriftBeforeAnyV13Ddl() throws SQLException {
        execute("ALTER TABLE investment_transactions DROP CONSTRAINT ck_investment_transactions_correction_fields");
        execute("ALTER TABLE investment_transactions ADD CONSTRAINT ck_investment_transactions_correction_fields CHECK (true)");

        assertV13FailsBeforeAnySchemaChange("expected V12 correction-fields constraint definition is incompatible");
    }

    @Test
    void rejectsSameNamedV12OriginalBindingForeignKeyDriftBeforeAnyV13Ddl() throws SQLException {
        execute("ALTER TABLE investment_transactions DROP CONSTRAINT fk_investment_transactions_original_binding");
        execute("""
                ALTER TABLE investment_transactions ADD CONSTRAINT fk_investment_transactions_original_binding
                FOREIGN KEY (user_id, original_transaction_id) REFERENCES investment_transactions (user_id, id)
                """);

        assertV13FailsBeforeAnySchemaChange("expected V12 fk_investment_transactions_original_binding constraint definition is incompatible");
    }

    @Test
    void rejectsSameNamedV12ForeignKeyDeleteActionDriftBeforeAnyV13Ddl() throws SQLException {
        execute("ALTER TABLE investment_transactions DROP CONSTRAINT fk_investment_transactions_user_asset");
        execute("""
                ALTER TABLE investment_transactions ADD CONSTRAINT fk_investment_transactions_user_asset
                FOREIGN KEY (user_id, asset_id) REFERENCES assets (user_id, id) ON DELETE CASCADE
                """);

        assertV13FailsBeforeAnySchemaChange("expected V12 fk_investment_transactions_user_asset constraint definition is incompatible");
    }

    @Test
    void rejectsSameNamedV12ReversalIndexDriftBeforeAnyV13Ddl() throws SQLException {
        execute("DROP INDEX uk_investment_transactions_reversal_original");
        execute("CREATE UNIQUE INDEX uk_investment_transactions_reversal_original ON investment_transactions (user_id, original_transaction_id) WHERE transaction_type = 'BUY'");

        assertV13FailsBeforeAnySchemaChange("expected V12 index definition is incompatible: uk_investment_transactions_reversal_original");
    }

    @Test
    void rejectsSameNamedV12ReversalAuditFunctionDriftBeforeAnyV13Ddl() throws SQLException {
        execute("""
                CREATE OR REPLACE FUNCTION validate_append_only_investment_reversal()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$
                """);

        assertV13FailsBeforeAnySchemaChange("expected V12 reversal audit function definition is incompatible");
    }

    @Test
    void rejectsSameNamedV12ImmutableFactFunctionDriftBeforeAnyV13Ddl() throws SQLException {
        execute("""
                CREATE OR REPLACE FUNCTION prevent_investment_transaction_mutation()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$
                """);

        assertV13FailsBeforeAnySchemaChange("expected V12 immutable fact function definition is incompatible");
    }

    @Test
    void rejectsSameNamedV12ReversalAuditTriggerDriftBeforeAnyV13Ddl() throws SQLException {
        execute("DROP TRIGGER trg_validate_append_only_investment_reversal ON investment_transactions");
        execute("""
                CREATE TRIGGER trg_validate_append_only_investment_reversal
                BEFORE UPDATE ON investment_transactions
                FOR EACH ROW EXECUTE FUNCTION validate_append_only_investment_reversal()
                """);

        assertV13FailsBeforeAnySchemaChange("expected V12 reversal audit trigger definition is incompatible");
    }

    @Test
    void rejectsSameNamedV12ImmutableFactTriggerDriftBeforeAnyV13Ddl() throws SQLException {
        execute("DROP TRIGGER trg_prevent_investment_transaction_mutation ON investment_transactions");
        execute("""
                CREATE TRIGGER trg_prevent_investment_transaction_mutation
                BEFORE INSERT ON investment_transactions
                FOR EACH ROW EXECUTE FUNCTION prevent_investment_transaction_mutation()
                """);

        assertV13FailsBeforeAnySchemaChange("expected V12 immutable fact trigger definition is incompatible");
    }

    @Test
    void installsTheCompleteCommandEnvelopeAndDeferredFactsFirstConstraints() throws SQLException {
        migrateThroughV13();

        assertThat(queryForInt("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'investment_transaction_corrections'
                  AND column_name IN ('instrument_id', 'transaction_type', 'correction_kind', 'idempotency_key',
                                      'request_hash', 'reversal_transaction_id', 'replacement_transaction_id',
                                      'reversal_cash_delta', 'replacement_cash_delta', 'command_cash_delta',
                                      'balance_after', 'position_quantity_after', 'position_avg_cost_after',
                                      'position_total_cost_after', 'position_realized_profit_loss_after',
                                      'position_status_after', 'projection_version', 'last_transaction_id', 'created_at')
                """)).isEqualTo(19);
        assertThat(queryForInt("""
                SELECT count(*) FROM pg_constraint
                WHERE condeferrable
                  AND conname IN ('fk_investment_transactions_correction_group_command',
                                  'fk_investment_transaction_corrections_reversal_fact',
                                  'fk_investment_transaction_corrections_replacement_fact')
                """)).isEqualTo(3);
        assertThat(queryForInt("""
                SELECT count(*) FROM pg_indexes
                WHERE tablename = 'investment_transactions'
                  AND indexname IN ('uk_investment_transactions_group_reversal',
                                    'uk_investment_transactions_group_replacement',
                                    'uk_investment_transactions_replacement_anchor')
                """)).isEqualTo(3);
        assertThat(queryForString("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'investment_transactions'
                  AND indexname = 'uk_investment_transactions_group_reversal'
                """)).contains("WHERE", "transaction_type", "REVERSAL", "correction_group_id IS NOT NULL");
        assertThat(queryForString("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'investment_transactions'
                  AND indexname = 'uk_investment_transactions_group_replacement'
                """)).contains("WHERE", "transaction_type", "REVERSAL", "correction_group_id IS NOT NULL");
        assertThat(queryForString("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'investment_transactions'::regclass
                  AND conname = 'ck_investment_transactions_receipt'
                """)).contains("correction_group_id");
        assertThat(queryForInt("""
                SELECT count(*) FROM pg_trigger
                WHERE tgname IN ('trg_validate_investment_replacement_fact',
                                 'trg_validate_investment_replacement_group_complete',
                                 'trg_validate_investment_replacement_command_complete',
                                 'trg_prevent_investment_transaction_correction_mutation')
                """)).isEqualTo(4);
    }

    @Test
    void commitsFactsFirstCommandLastAndRejectsIncompleteOrMutableCorrectionGroups() throws SQLException {
        insertV12Buy();
        migrateThroughV13();

        insertCompleteReplacementGroup();

        assertThat(queryForInt("SELECT count(*) FROM investment_transaction_corrections")).isEqualTo(1);
        assertThat(queryForInt("""
                SELECT count(*) FROM investment_transactions
                WHERE correction_group_id = '00000000-0000-0000-0000-000000000013'
                  AND transaction_type = 'REVERSAL'
                  AND account_balance_after IS NULL
                  AND position_quantity_after IS NULL
                  AND position_avg_cost_after IS NULL
                  AND position_total_cost_after IS NULL
                  AND position_realized_profit_loss_after IS NULL
                  AND position_status_after IS NULL
                  AND projection_version_after IS NULL
                """)).isEqualTo(1);
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> execute("UPDATE investment_transaction_corrections SET correction_reason = 'changed' WHERE id = 1")))
                .hasMessageContaining("investment transaction correction commands are immutable");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> execute("UPDATE investment_transactions SET note = 'changed' WHERE id = 3")))
                .hasMessageContaining("investment transaction facts are immutable");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> execute("DELETE FROM investment_transaction_corrections WHERE id = 1")))
                .hasMessageContaining("investment transaction correction commands are immutable");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> execute("DELETE FROM investment_transactions WHERE id = 3")))
                .hasMessageContaining("investment transaction facts are immutable");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(this::insertDuplicateReplacementFact))
                .hasMessageContaining("uk_investment_transactions_group_replacement");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(this::insertIncompleteReplacementGroup))
                .hasMessageContaining("fk_investment_transactions_correction_group_command");
        assertThat(queryForInt("SELECT count(*) FROM investment_transactions "
                + "WHERE correction_group_id = '00000000-0000-0000-0000-000000000014'")).isZero();
        assertThat(queryForInt("SELECT count(*) FROM investment_transaction_corrections "
                + "WHERE correction_group_id = '00000000-0000-0000-0000-000000000014'")).isZero();
    }

    @Test
    void rejectsACommandWhoseCompleteReceiptDoesNotMatchTheReplacementFact() throws SQLException {
        insertV12Buy();
        migrateThroughV13();

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertCompleteReplacementGroup("Different command reason")))
                .hasMessageContaining("replacement correction group binding is invalid");
        assertThat(queryForInt("SELECT count(*) FROM investment_transaction_corrections")).isZero();
    }

    @Test
    void rejectsACompleteGroupWhoseCommandTypeDoesNotMatchItsOriginalFact() throws SQLException {
        insertV12Buy();
        migrateThroughV13();

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertCompleteReplacementGroup("Correct purchase price", "SELL")))
                .hasMessageContaining("replacement correction group binding is invalid");
        assertThat(queryForInt("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL")).isZero();
        assertThat(queryForInt("SELECT count(*) FROM investment_transaction_corrections")).isZero();
    }

    @Test
    void rejectsACompleteGroupWhoseCommandInstrumentDoesNotMatchTheAssetBinding() throws SQLException {
        insertV12Buy();
        execute("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (1, 'FUND2', 'Other Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')
                """);
        migrateThroughV13();

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertCompleteReplacementGroup("Correct purchase price", "BUY", 2)))
                .hasMessageContaining("replacement correction group binding is invalid");
        assertThat(queryForInt("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL")).isZero();
        assertThat(queryForInt("SELECT count(*) FROM investment_transaction_corrections")).isZero();
    }

    @Test
    void rejectsCommandUserAccountAndAssetBindingsThatDoNotMatchTheOriginalFact() throws SQLException {
        insertV12Buy();
        migrateThroughV13();

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertCompleteReplacementGroup("Correct purchase price", "BUY", 1, 2, 1, 1)))
                .isNotNull();
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertCompleteReplacementGroup("Correct purchase price", "BUY", 1, 1, 2, 1)))
                .isNotNull();
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertCompleteReplacementGroup("Correct purchase price", "BUY", 2, 1, 1, 1)))
                .isNotNull();
        assertThat(queryForInt("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL")).isZero();
        assertThat(queryForInt("SELECT count(*) FROM investment_transaction_corrections")).isZero();
    }

    @Test
    void rejectsReplacementFactsWithInvalidAnchorsAndOriginalBindingMetadata() throws SQLException {
        insertV12Buy();
        migrateThroughV13();

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertReplacementFact("00000000-0000-0000-0000-000000000021", "999", "currency", "trade_time", "settlement_time")))
                .hasMessageContaining("investment replacement anchor is invalid");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertReplacementFact("00000000-0000-0000-0000-000000000022", "id", "'USD'", "trade_time", "settlement_time")))
                .isNotNull();
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertReplacementFact("00000000-0000-0000-0000-000000000023", "id", "currency", "trade_time + INTERVAL '1 day'", "settlement_time + INTERVAL '1 day'")))
                .hasMessageContaining("investment replacement fact does not preserve its original binding");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertReplacementFact("00000000-0000-0000-0000-000000000024", "id", "currency", "trade_time", "settlement_time + INTERVAL '1 day'")))
                .hasMessageContaining("investment replacement fact does not preserve its original binding");
        assertThat(queryForInt("SELECT count(*) FROM investment_transactions WHERE correction_group_id IS NOT NULL")).isZero();
    }

    @Test
    void preservesTheStandaloneAndGroupedReversalReceiptSplitAndRejectsRecorrectingAFact() throws SQLException {
        insertV12Buy();
        migrateThroughV13();

        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertGroupedReversalWithLegacyReceipt("00000000-0000-0000-0000-000000000025")))
                .hasMessageContaining("ck_investment_transactions_receipt");
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(this::insertStandaloneReversalWithoutLegacyReceipt))
                .hasMessageContaining("ck_investment_transactions_receipt");

        insertCompleteReplacementGroup();
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(
                () -> insertReversalAgainstCorrectionFact("00000000-0000-0000-0000-000000000026")))
                .hasMessageContaining("investment correction facts cannot be corrected again");
    }

    private void assertV13FailsBeforeAnySchemaChange(String expectedMessage) throws SQLException {
        assertThat(org.assertj.core.api.ThrowableAssert.catchThrowable(this::migrateThroughV13))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining(expectedMessage);
        assertThat(queryForInt("SELECT count(*) FROM flyway_schema_history WHERE version = '13'")).isZero();
        assertThat(queryForInt("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_name = 'investment_transaction_corrections'")).isZero();
    }

    private void migrateThroughV13() {
        Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(migrationLocation()).load().migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(migrationLocation()).cleanDisabled(false).target(target).load();
    }

    private String migrationLocation() {
        return "filesystem:" + Path.of("src", "main", "resources", "db", "migration").toAbsolutePath();
    }

    private void insertV12Buy() throws SQLException {
        execute("INSERT INTO users (username, email, password_hash) VALUES ('replacement', 'replacement@example.com', 'hash')");
        execute("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (1, 'Brokerage', 'BROKERAGE', 'CNY', 0)");
        execute("INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status) VALUES (1, 'FUND1', 'Fund', 'FUND', 'FUND', 'CNY', 'ACTIVE')");
        execute("""
                INSERT INTO assets (user_id, account_id, instrument_id, name, symbol, type, market, currency, quantity, avg_cost, total_cost, realized_profit_loss, position_status, position_mode)
                VALUES (1, 1, 1, 'Fund', 'FUND1', 'FUND', 'FUND', 'CNY', 1.00000000, 1.00000000, 1.00, 0.00, 'OPEN', 'TRANSACTION_DRIVEN')
                """);
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash,
                    account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                    position_realized_profit_loss_after, position_status_after, projection_version_after)
                VALUES (1, 1, 1, 'BUY', 'POSTED', 1.00000000, 1.00000000, 1.00, 0.00, 0.00, 1.00, 0.00, 0.00,
                        'CNY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'MANUAL', 'replacement-buy', repeat('a', 64),
                        -1.00, 1.00000000, 1.00000000, 1.00, 0.00, 'OPEN', 1)
                """);
    }

    private void insertV12StandaloneReversal() throws SQLException {
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash,
                    account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                    position_realized_profit_loss_after, position_status_after, projection_version_after,
                    original_transaction_id, correction_reason, cash_delta)
                SELECT user_id, asset_id, account_id, 'REVERSAL', 'POSTED', quantity, unit_price, gross_amount,
                       fee_amount, tax_amount, net_amount, 0.00, 0.00, currency,
                       trade_time, settlement_time, 'CORRECTION', 'replacement-standalone-reversal', repeat('b', 64),
                       0.00, 0.00000000, 0.00000000, 0.00, 0.00, 'CLOSED', 2,
                       id, 'Correct a standalone posting', net_amount
                FROM investment_transactions
                WHERE id = 1
                """);
    }

    private void insertCompleteReplacementGroup() throws SQLException {
        insertCompleteReplacementGroup("Correct purchase price");
    }

    private void insertCompleteReplacementGroup(String commandReason) throws SQLException {
        insertCompleteReplacementGroup(commandReason, "BUY");
    }

    private void insertCompleteReplacementGroup(String commandReason, String commandTransactionType) throws SQLException {
        insertCompleteReplacementGroup(commandReason, commandTransactionType, 1);
    }

    private void insertCompleteReplacementGroup(String commandReason, String commandTransactionType, int commandInstrumentId) throws SQLException {
        insertCompleteReplacementGroup(commandReason, commandTransactionType, 1, 1, 1, commandInstrumentId);
    }

    private void insertCompleteReplacementGroup(String commandReason, String commandTransactionType, int commandUserId,
                                                int commandAccountId, int commandAssetId, int commandInstrumentId) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    INSERT INTO investment_transactions (
                        user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                        fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                        trade_time, settlement_time, source, idempotency_key, request_hash,
                        account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                        position_realized_profit_loss_after, position_status_after, projection_version_after,
                        original_transaction_id, correction_reason, cash_delta,
                        correction_group_id, replay_anchor_transaction_id, replay_sequence)
                    SELECT user_id, asset_id, account_id, 'REVERSAL', 'POSTED', quantity, unit_price, gross_amount,
                           fee_amount, tax_amount, net_amount, 0.00, 0.00, currency, trade_time, settlement_time,
                           'CORRECTION', 'replacement-group-reversal', repeat('c', 64),
                           NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                           id, 'Correct purchase price', net_amount,
                           '00000000-0000-0000-0000-000000000013', NULL, 0
                    FROM investment_transactions WHERE id = 1
                    """);
            statement.execute("""
                    INSERT INTO investment_transactions (
                        user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                        fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                        trade_time, settlement_time, source, idempotency_key, request_hash,
                        account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                        position_realized_profit_loss_after, position_status_after, projection_version_after,
                        original_transaction_id, correction_reason, cash_delta,
                        correction_group_id, replay_anchor_transaction_id, replay_sequence)
                    SELECT user_id, asset_id, account_id, 'BUY', 'POSTED', quantity, 2.00000000, 2.00,
                           0.00, 0.00, 2.00, 0.00, 0.00, currency, trade_time, settlement_time,
                           'CORRECTION', 'replacement-group-fact', repeat('d', 64),
                           -2.00, quantity, 2.00000000, 2.00, 0.00, 'OPEN', 2,
                           NULL, NULL, -2.00,
                           '00000000-0000-0000-0000-000000000013', id, 1
                    FROM investment_transactions WHERE id = 1
                    """);
            long reversalTransactionId;
            long replacementTransactionId;
            try (var result = statement.executeQuery("""
                    SELECT max(id) FILTER (WHERE transaction_type = 'REVERSAL'),
                           max(id) FILTER (WHERE transaction_type <> 'REVERSAL')
                    FROM investment_transactions
                    WHERE correction_group_id = '00000000-0000-0000-0000-000000000013'
                    """)) {
                result.next();
                reversalTransactionId = result.getLong(1);
                replacementTransactionId = result.getLong(2);
            }
            statement.execute("""
                    INSERT INTO investment_transaction_corrections (
                        correction_group_id, user_id, account_id, asset_id, instrument_id, original_transaction_id,
                        transaction_type, correction_kind, idempotency_key, request_hash, correction_reason,
                        reversal_transaction_id, replacement_transaction_id, reversal_cash_delta, replacement_cash_delta,
                        command_cash_delta, balance_after, position_quantity_after, position_avg_cost_after,
                        position_total_cost_after, position_realized_profit_loss_after, position_status_after,
                        projection_version, last_transaction_id, created_at)
                    VALUES (
                        '00000000-0000-0000-0000-000000000013', %d, %d, %d, %d, 1,
                        '%s', 'REPLACEMENT', 'replacement-command', repeat('e', 64), '%s',
                        %d, %d, 1.00, -2.00, -1.00, -2.00, 1.00000000, 2.00000000,
                        2.00, 0.00, 'OPEN', 2, %d, CURRENT_TIMESTAMP)
                    """.formatted(commandUserId, commandAccountId, commandAssetId, commandInstrumentId,
                            commandTransactionType, commandReason, reversalTransactionId, replacementTransactionId,
                            replacementTransactionId));
            connection.commit();
        }
    }

    private void insertIncompleteReplacementGroup() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    INSERT INTO investment_transactions (
                        user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                        fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                        trade_time, settlement_time, source, idempotency_key, request_hash,
                        account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                        position_realized_profit_loss_after, position_status_after, projection_version_after,
                        original_transaction_id, correction_reason, cash_delta,
                        correction_group_id, replay_anchor_transaction_id, replay_sequence)
                    SELECT user_id, asset_id, account_id, 'BUY', 'POSTED', quantity, unit_price, gross_amount,
                           fee_amount, tax_amount, net_amount, 0.00, 0.00, currency, trade_time, settlement_time,
                           'MANUAL', 'incomplete-group-original', repeat('f', 64),
                           -1.00, quantity, unit_price, net_amount, 0.00, 'OPEN', 3,
                           NULL, NULL, NULL,
                           NULL, NULL, 0
                    FROM investment_transactions WHERE id = 1
                    """);
            statement.execute("""
                    INSERT INTO investment_transactions (
                        user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                        fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                        trade_time, settlement_time, source, idempotency_key, request_hash,
                        account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                        position_realized_profit_loss_after, position_status_after, projection_version_after,
                        original_transaction_id, correction_reason, cash_delta,
                        correction_group_id, replay_anchor_transaction_id, replay_sequence)
                    SELECT user_id, asset_id, account_id, 'BUY', 'POSTED', quantity, unit_price, gross_amount,
                           fee_amount, tax_amount, net_amount, 0.00, 0.00, currency, trade_time, settlement_time,
                           'CORRECTION', 'incomplete-group-replacement', repeat('a', 64),
                           -1.00, quantity, unit_price, net_amount, 0.00, 'OPEN', 4,
                           NULL, NULL, -1.00,
                           '00000000-0000-0000-0000-000000000014', id, 1
                    FROM investment_transactions WHERE idempotency_key = 'incomplete-group-original'
                    """);
            connection.commit();
        }
    }

    private void insertDuplicateReplacementFact() throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            int inserted = statement.executeUpdate("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, note, external_reference, source, idempotency_key, request_hash,
                    account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                    position_realized_profit_loss_after, position_status_after, projection_version_after,
                    original_transaction_id, correction_reason, cash_delta,
                    correction_group_id, replay_anchor_transaction_id, replay_sequence)
                SELECT user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                       fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                       trade_time, settlement_time, note, external_reference, source, 'replacement-group-fact-duplicate', repeat('f', 64),
                       account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                       position_realized_profit_loss_after, position_status_after, projection_version_after,
                       original_transaction_id, correction_reason, cash_delta,
                       correction_group_id, replay_anchor_transaction_id, replay_sequence
                FROM investment_transactions
                WHERE correction_group_id = '00000000-0000-0000-0000-000000000013'
                  AND transaction_type <> 'REVERSAL'
                """);
            if (inserted != 1) {
                throw new SQLException("Expected one replacement fact to duplicate, but found " + inserted);
            }
        }
    }

    private void insertReplacementFact(String groupId, String anchorExpression, String currencyExpression,
                                       String tradeTimeExpression, String settlementTimeExpression) throws SQLException {
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash,
                    account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                    position_realized_profit_loss_after, position_status_after, projection_version_after,
                    original_transaction_id, correction_reason, cash_delta,
                    correction_group_id, replay_anchor_transaction_id, replay_sequence)
                SELECT user_id, asset_id, account_id, 'BUY', 'POSTED', quantity, unit_price, gross_amount,
                       fee_amount, tax_amount, net_amount, 0.00, 0.00, %s,
                       %s, %s, 'CORRECTION', 'replacement-binding-%s', repeat('9', 64),
                       account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                       position_realized_profit_loss_after, position_status_after, projection_version_after,
                       NULL, NULL, -net_amount,
                       '%s', %s, 1
                FROM investment_transactions WHERE id = 1
                """.formatted(currencyExpression, tradeTimeExpression, settlementTimeExpression,
                groupId.substring(groupId.length() - 2), groupId, anchorExpression));
    }

    private void insertGroupedReversalWithLegacyReceipt(String groupId) throws SQLException {
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash,
                    account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                    position_realized_profit_loss_after, position_status_after, projection_version_after,
                    original_transaction_id, correction_reason, cash_delta, correction_group_id, replay_sequence)
                SELECT user_id, asset_id, account_id, 'REVERSAL', 'POSTED', quantity, unit_price, gross_amount,
                       fee_amount, tax_amount, net_amount, 0.00, 0.00, currency, trade_time, settlement_time,
                       'CORRECTION', 'grouped-reversal-with-receipt', repeat('8', 64),
                       account_balance_after, position_quantity_after, position_avg_cost_after, position_total_cost_after,
                       position_realized_profit_loss_after, position_status_after, projection_version_after,
                       id, 'receipt must be null', net_amount, '%s', 0
                FROM investment_transactions WHERE id = 1
                """.formatted(groupId));
    }

    private void insertStandaloneReversalWithoutLegacyReceipt() throws SQLException {
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash,
                    original_transaction_id, correction_reason, cash_delta)
                SELECT user_id, asset_id, account_id, 'REVERSAL', 'POSTED', quantity, unit_price, gross_amount,
                       fee_amount, tax_amount, net_amount, 0.00, 0.00, currency, trade_time, settlement_time,
                       'CORRECTION', 'standalone-reversal-without-receipt', repeat('7', 64),
                       id, 'legacy receipt is required', net_amount
                FROM investment_transactions WHERE id = 1
                """);
    }

    private void insertReversalAgainstCorrectionFact(String groupId) throws SQLException {
        execute("""
                INSERT INTO investment_transactions (
                    user_id, asset_id, account_id, transaction_type, status, quantity, unit_price, gross_amount,
                    fee_amount, tax_amount, net_amount, released_cost_amount, realized_profit_loss, currency,
                    trade_time, settlement_time, source, idempotency_key, request_hash,
                    original_transaction_id, correction_reason, cash_delta, correction_group_id, replay_sequence)
                SELECT user_id, asset_id, account_id, 'REVERSAL', 'POSTED', quantity, unit_price, gross_amount,
                       fee_amount, tax_amount, net_amount, 0.00, 0.00, currency, trade_time, settlement_time,
                       'CORRECTION', 'correction-fact-reversal', repeat('6', 64),
                       id, 'correction fact cannot be corrected', net_amount, '%s', 0
                FROM investment_transactions
                WHERE correction_group_id = '00000000-0000-0000-0000-000000000013'
                  AND transaction_type <> 'REVERSAL'
                """.formatted(groupId));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int queryForInt(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String queryForString(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
    }
}
