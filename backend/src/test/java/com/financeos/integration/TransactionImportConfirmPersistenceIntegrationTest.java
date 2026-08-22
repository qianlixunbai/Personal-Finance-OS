package com.financeos.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionImportConfirmPersistenceIntegrationTest extends PostgresIntegrationTest {

    @Test
    void v16ProvidesTheImmutableReceiptPersistenceContract() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'transaction_import_batches' AND column_name IN ('session_id', 'result_digest')
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name = 'transaction_import_batch_account_impacts'
                """, Integer.class)).isEqualTo(1);
    }
}
