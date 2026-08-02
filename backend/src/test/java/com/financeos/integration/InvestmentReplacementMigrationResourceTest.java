package com.financeos.integration;

import com.financeos.module.investment.entity.InvestmentTransactionCorrection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentReplacementMigrationResourceTest {

    @Test
    void definesTheV13ReplacementCommandAndReplayAnchorSchema() throws IOException {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V13__add_investment_transaction_replacements.sql"));

        assertThat(migration).contains(
                "CREATE TABLE investment_transaction_corrections",
                "correction_group_id UUID",
                "replay_anchor_transaction_id BIGINT",
                "replay_sequence SMALLINT NOT NULL DEFAULT 0",
                "trg_prevent_investment_transaction_correction_mutation",
                "trg_validate_investment_replacement_group_complete");
    }

    @Test
    void preservesStandaloneReversalReceiptsWhileMakingOnlyGroupedReversalsReceiptless() throws IOException {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V13__add_investment_transaction_replacements.sql"));

        assertThat(migration).contains(
                "transaction_type = 'REVERSAL' AND correction_group_id IS NOT NULL",
                "transaction_type = 'REVERSAL' AND correction_group_id IS NULL",
                "replay_anchor_transaction_id IS NULL",
                "replay_sequence = 1");
    }

    @Test
    void establishesAnImmutableCompleteCommandEnvelopeAndDeferredFactsFirstBinding() throws IOException {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V13__add_investment_transaction_replacements.sql"));

        assertThat(migration).contains(
                "instrument_id BIGINT NOT NULL",
                "transaction_type VARCHAR(30) NOT NULL",
                "correction_kind VARCHAR(30) NOT NULL",
                "idempotency_key VARCHAR(100) NOT NULL",
                "request_hash CHAR(64) NOT NULL",
                "reversal_transaction_id BIGINT NOT NULL",
                "replacement_transaction_id BIGINT NOT NULL",
                "balance_after NUMERIC(18, 2) NOT NULL",
                "projection_version INTEGER NOT NULL",
                "last_transaction_id BIGINT NOT NULL",
                "FOREIGN KEY (user_id, correction_group_id)",
                "DEFERRABLE INITIALLY DEFERRED",
                "trg_validate_investment_replacement_group_complete");
    }

    @Test
    void verifiesV12DefinitionsBeforeReplacingReceiptConstraints() throws IOException {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V13__add_investment_transaction_replacements.sql"));

        assertThat(migration).contains(
                "pg_get_constraintdef(oid)",
                "pg_get_functiondef(to_regprocedure('validate_append_only_investment_reversal()'))",
                "pg_get_functiondef(to_regprocedure('prevent_investment_transaction_mutation()'))",
                "pg_get_triggerdef(oid)",
                "expected V12 receipt constraint definition is incompatible",
                "expected V12 correction-fields constraint definition is incompatible",
                "expected V12 reversal audit trigger definition is incompatible",
                "expected V12 immutable fact trigger definition is incompatible");
    }

    @Test
    void comparesCompleteNormalizedV12DefinitionsBeforeAnyDestructiveDdl() throws IOException {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V13__add_investment_transaction_replacements.sql"));

        assertThat(migration).contains(
                "regexp_replace(regexp_replace(lower(replace(actual_definition, 'public.', ''))",
                "'ck_investment_transactions_type'",
                "'ck_investment_transactions_status'",
                "'ck_investment_transactions_source'",
                "'ck_investment_transactions_legacy_correction_fields_empty'",
                "'ck_investment_transactions_type_fields'",
                "idx_investment_transactions_user_trade_time_desc",
                "uk_investment_transactions_posted_opening_asset",
                "expected V12 index definition is incompatible:",
                "pg_temp(_[0-9]+)?\\.",
                "expected V12 reversal audit function definition is incompatible",
                "expected V12 immutable fact function definition is incompatible",
                "expected V12 reversal audit trigger definition is incompatible",
                "expected V12 immutable fact trigger definition is incompatible");
        assertThat(migration).doesNotContain("receipt_definition NOT LIKE", "correction_fields_definition NOT LIKE");
    }

    @Test
    void validatesEveryRequiredV12ColumnTypeNullabilityAndDefaultBeforeAnyDdl() throws IOException {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V13__add_investment_transaction_replacements.sql"));

        assertThat(migration).contains(
                "v13_expected_v12_column_definitions",
                "column_name, expected_type, expected_not_null, expected_default",
                "'transaction_type', 'character varying(20)', true, NULL",
                "'status', 'character varying(20)', true, '''POSTED''::character varying'",
                "'original_transaction_id', 'bigint', false, NULL",
                "'correction_reason', 'character varying(500)', false, NULL",
                "'cash_delta', 'numeric(28,2)', false, NULL",
                "'projection_version_after', 'integer', false, NULL",
                "<> 37",
                "expected V12 investment transaction column count is incompatible",
                "expected V12 column definition is incompatible");
    }

    @Test
    void validatesTheCommandReceiptAgainstTheReplacementFactCompletely() throws IOException {
        String migration = Files.readString(Path.of("src", "main", "resources", "db", "migration",
                "V13__add_investment_transaction_replacements.sql"));

        assertThat(migration).contains(
                "command_row.correction_reason IS DISTINCT FROM reversal_row.correction_reason",
                "command_row.balance_after IS DISTINCT FROM replacement_row.account_balance_after",
                "command_row.position_quantity_after IS DISTINCT FROM replacement_row.position_quantity_after",
                "command_row.position_avg_cost_after IS DISTINCT FROM replacement_row.position_avg_cost_after",
                "command_row.position_total_cost_after IS DISTINCT FROM replacement_row.position_total_cost_after",
                "command_row.position_realized_profit_loss_after IS DISTINCT FROM replacement_row.position_realized_profit_loss_after",
                "command_row.position_status_after IS DISTINCT FROM replacement_row.position_status_after",
                "command_row.projection_version IS DISTINCT FROM replacement_row.projection_version_after");
    }

    @Test
    void exposesTheCorrectionEnvelopeAsAnInsertableOwnedReadModel() throws Exception {
        Class<?> entity = Class.forName("com.financeos.module.investment.entity.InvestmentTransactionCorrection");
        Class<?> mapper = Class.forName("com.financeos.module.investment.mapper.InvestmentTransactionCorrectionMapper");

        assertThat(entity.getDeclaredField("correctionGroupId")).isNotNull();
        assertThat(entity.getDeclaredField("replacementTransactionId")).isNotNull();
        assertThat(mapper.getDeclaredMethod("findByUserIdAndId", Long.class, Long.class)).isNotNull();
        assertThat(mapper.getDeclaredMethod("insert", InvestmentTransactionCorrection.class)).isNotNull();
        assertThat(mapper.getInterfaces()).isEmpty();
    }
}
