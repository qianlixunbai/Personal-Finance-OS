package com.financeos.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FlywayTransactionImportVersionTwentyOneMigrationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:17-alpine");
    JdbcTemplate sql() { return new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())); }
    Flyway migration(String target) {
        return Flyway.configure().dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(false).load();
    }
    @AfterEach void clearOwnedDatabase() { migration("21").clean(); }

    @Test void emptyDatabaseMigratesThroughV21WithDurableRegistryConstraints() {
        migration("21").migrate();
        var db = sql();
        assertThat(db.queryForObject("SELECT MAX(version::INTEGER) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class)).isEqualTo(21);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE tablename='transaction_import_file_objects'", Integer.class)).isEqualTo(3);
        UUID deployment = UUID.randomUUID(), storage = UUID.randomUUID(), intended = UUID.randomUUID();
        db.update("INSERT INTO transaction_import_storage_bindings(storage_id,deployment_id,kind,binding_digest) VALUES(?,?,'RAW',repeat('a',64))", storage, deployment);
        String insert = "INSERT INTO transaction_import_file_objects(reference,storage_id,kind,intended_session_id,state,lease_owner,lease_until) VALUES(?,?,'RAW',?, ?,gen_random_uuid(),CURRENT_TIMESTAMP)";
        assertThatThrownBy(() -> db.update(insert, "../escape", storage, intended, "RESERVED")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(insert, UUID.randomUUID().toString(), storage, intended, "LIVE")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update(insert, UUID.randomUUID().toString(), storage, intended, "DELETED")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        String ref = UUID.randomUUID().toString();
        db.update(insert, ref, storage, intended, "RESERVED");
        db.update("UPDATE transaction_import_file_objects SET state='RETIRED',fence_epoch=fence_epoch+1,attempt_count=attempt_count+1,last_failure_code='STORAGE_UNAVAILABLE' WHERE reference=?", ref);
        assertThat(db.queryForObject("SELECT fence_epoch FROM transaction_import_file_objects WHERE reference=?", Long.class, ref)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT attempt_count FROM transaction_import_file_objects WHERE reference=?", Integer.class, ref)).isEqualTo(1);
    }

    @Test void genuineV20UpgradePreservesFinancialFactsReceiptsAndUnregisteredLegacyReferences() {
        migration("20").migrate();
        var db = sql();
        db.update("INSERT INTO users(username,email,password_hash) VALUES('c9-migration','c9-migration@example.com','synthetic-fixture')");
        db.update("INSERT INTO accounts(user_id,name,type,currency,balance) VALUES(1,'Cash','CASH','CNY',90)");
        db.update("INSERT INTO categories(user_id,name,type) VALUES(1,'Food','EXPENSE')");
        db.update("INSERT INTO transactions(user_id,account_id,category_id,type,amount,currency,description,transacted_at) VALUES(1,1,1,'EXPENSE',10,'CNY','synthetic migration fact',TIMESTAMP '2026-08-01 00:00:00')");
        UUID consumed = UUID.randomUUID(), batch = UUID.randomUUID(), active = UUID.randomUUID();
        db.update("""
                INSERT INTO transaction_import_sessions(id,user_id,preallocated_batch_id,status,revision,original_file_name,file_size,file_digest,temporary_storage_reference,mapping_digest,options_digest,normalized_rows_digest,expires_at,consumed_at)
                VALUES(?,1,?,'CONSUMED',1,'synthetic.csv',10,repeat('a',64),NULL,repeat('b',64),repeat('c',64),repeat('d',64),CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP)
                """, consumed, batch);
        db.update("""
                INSERT INTO transaction_import_batches(id,user_id,original_file_name,file_digest,mapping_digest,options_digest,normalized_rows_digest,contract_version,idempotency_key,request_hash,status,total_rows,warning_count,confirmed_at,session_id,result_digest)
                VALUES(?,1,'synthetic.csv',repeat('a',64),repeat('b',64),repeat('c',64),repeat('d',64),'TRANSACTION_IMPORT_CONFIRM_V1','c9-upgrade',repeat('e',64),'CONFIRMED',1,0,CURRENT_TIMESTAMP,?,repeat('f',64))
                """, batch, consumed);
        db.update("INSERT INTO transaction_import_items(user_id,batch_id,source_row_number,canonical_row_fingerprint,created_transaction_id) VALUES(1,?,1,repeat('a',64),1)", batch);
        db.update("INSERT INTO transaction_import_batch_account_impacts(user_id,batch_id,account_id,row_count,balance_before,delta,balance_after) VALUES(1,?,1,1,100,-10,90)", batch);
        String legacy = UUID.randomUUID().toString();
        db.update("""
                INSERT INTO transaction_import_sessions(id,user_id,preallocated_batch_id,status,original_file_name,file_size,file_digest,temporary_storage_reference,options_digest,expires_at)
                VALUES(?,1,?,'MAPPING_REQUIRED','legacy-synthetic.csv',10,repeat('a',64),?,repeat('c',64),CURRENT_TIMESTAMP+INTERVAL '1 hour')
                """, active, UUID.randomUUID(), legacy);
        var before = snapshot(db);
        assertThat(migration("21").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(snapshot(db)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_import_file_objects", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM transaction_import_storage_bindings", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT temporary_storage_reference FROM transaction_import_sessions WHERE id=?", String.class, active)).isEqualTo(legacy);
        assertThatThrownBy(() -> db.update("UPDATE transaction_import_batches SET result_digest=repeat('0',64) WHERE id=?", batch)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        migration("21").validate();
    }
    private Map<String,String> snapshot(JdbcTemplate db) {
        Map<String,String> result = new TreeMap<>();
        for (String table : List.of("users","accounts","categories","transactions","transaction_import_sessions","transaction_import_batches","transaction_import_items","transaction_import_batch_account_impacts"))
            result.put(table, db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]') FROM " + table + " t", String.class));
        return result;
    }
}
