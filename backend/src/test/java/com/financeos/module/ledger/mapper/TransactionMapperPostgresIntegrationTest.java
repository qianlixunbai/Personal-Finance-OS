package com.financeos.module.ledger.mapper;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionMapperPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionMapper transactionMapper;

    @Test
    void aggregatesIncomeAndExpenseInMonthOrderAndMapsMonthStartToLocalDate() {
        Long userId = insertUser("trend-user");
        Long accountId = insertAccount(userId);
        Long categoryId = insertCategory(userId);
        insertTransaction(userId, accountId, categoryId, "INCOME", "100.10", "CNY", LocalDateTime.of(2025, 12, 1, 0, 0));
        insertTransaction(userId, accountId, categoryId, "EXPENSE", "20.20", "CNY", LocalDateTime.of(2025, 12, 15, 12, 0));
        insertTransaction(userId, accountId, categoryId, "INCOME", "200.00", "CNY", LocalDateTime.of(2026, 1, 10, 9, 0));
        insertTransaction(userId, accountId, categoryId, "EXPENSE", "80.05", "CNY", LocalDateTime.of(2026, 1, 31, 23, 59));

        List<MonthlyCashFlowAggregate> result = transactionMapper.monthlyCashFlowByMonth(
                userId, LocalDateTime.of(2025, 12, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0));

        assertThat(result).extracting(MonthlyCashFlowAggregate::monthStart)
                .containsExactly(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 1));
        assertThat(result.get(0).income()).isEqualByComparingTo(new BigDecimal("100.10"));
        assertThat(result.get(0).expense()).isEqualByComparingTo(new BigDecimal("20.20"));
        assertThat(result.get(1).income()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(result.get(1).expense()).isEqualByComparingTo(new BigDecimal("80.05"));
    }

    @Test
    void filtersOtherUsersCurrenciesAndTypesAndHonorsTimeBounds() {
        Long currentUserId = insertUser("current-user");
        Long otherUserId = insertUser("other-user");
        Long currentAccountId = insertAccount(currentUserId);
        Long otherAccountId = insertAccount(otherUserId);
        Long currentCategoryId = insertCategory(currentUserId);
        Long otherCategoryId = insertCategory(otherUserId);
        LocalDateTime startInclusive = LocalDateTime.of(2026, 3, 1, 0, 0);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 4, 1, 0, 0);

        insertTransaction(currentUserId, currentAccountId, currentCategoryId, "INCOME", "10.00", "CNY", startInclusive);
        insertTransaction(currentUserId, currentAccountId, currentCategoryId, "EXPENSE", "2.50", "CNY", LocalDateTime.of(2026, 3, 31, 23, 59));
        insertTransaction(currentUserId, currentAccountId, currentCategoryId, "INCOME", "700.00", "USD", LocalDateTime.of(2026, 3, 10, 12, 0));
        insertTransaction(currentUserId, currentAccountId, currentCategoryId, "ADJUSTMENT", "800.00", "CNY", LocalDateTime.of(2026, 3, 11, 12, 0));
        insertTransaction(currentUserId, currentAccountId, currentCategoryId, "TRANSFER", "900.00", "CNY", LocalDateTime.of(2026, 3, 12, 12, 0));
        insertTransaction(otherUserId, otherAccountId, otherCategoryId, "INCOME", "600.00", "CNY", LocalDateTime.of(2026, 3, 13, 12, 0));
        insertTransaction(currentUserId, currentAccountId, currentCategoryId, "INCOME", "500.00", "CNY", endExclusive);

        List<MonthlyCashFlowAggregate> result = transactionMapper.monthlyCashFlowByMonth(
                currentUserId, startInclusive, endExclusive);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().monthStart()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(result.getFirst().income()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(result.getFirst().expense()).isEqualByComparingTo(new BigDecimal("2.50"));
    }

    @Test
    void returnsEmptyCollectionWhenNoTransactionMatches() {
        Long userId = insertUser("empty-user");

        List<MonthlyCashFlowAggregate> result = transactionMapper.monthlyCashFlowByMonth(
                userId, LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 2, 1, 0, 0));

        assertThat(result).isEmpty();
    }

    @Test
    void createsTheMonthlyTrendCompositeIndex() {
        String columns = jdbcTemplate.queryForObject("""
                SELECT string_agg(attribute.attname, ',' ORDER BY key_column.ordinality)
                FROM pg_index index_definition
                JOIN pg_class index_class ON index_class.oid = index_definition.indexrelid
                CROSS JOIN LATERAL unnest(index_definition.indkey) WITH ORDINALITY AS key_column(attnum, ordinality)
                JOIN pg_attribute attribute ON attribute.attrelid = index_definition.indrelid
                    AND attribute.attnum = key_column.attnum
                WHERE index_class.relname = 'idx_transactions_user_currency_type_time'
                """, String.class);

        assertThat(columns).isEqualTo("user_id,currency,type,transacted_at");
    }

    private Long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES (?, ?, 'hash')
                RETURNING id
                """, Long.class, username, username + "@example.com");
    }

    private Long insertAccount(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Test account', 'BANK', 'CNY', 0)
                RETURNING id
                """, Long.class, userId);
    }

    private Long insertCategory(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO categories (user_id, name, type)
                VALUES (?, 'Test category', 'EXPENSE')
                RETURNING id
                """, Long.class, userId);
    }

    private void insertTransaction(Long userId, Long accountId, Long categoryId, String type,
                                   String amount, String currency, LocalDateTime transactedAt) {
        jdbcTemplate.update("""
                INSERT INTO transactions (user_id, account_id, category_id, type, amount, currency, transacted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, userId, accountId, categoryId, type, new BigDecimal(amount), currency, transactedAt);
    }
}
