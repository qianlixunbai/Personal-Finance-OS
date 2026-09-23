package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-2: duplicate-submit protection for {@code POST /api/v1/transactions}.
 *
 * <p>These tests pin the four properties that matter for a money write: one key never
 * creates two facts, the balance delta is applied once, a replay returns the authoritative
 * first result, and a reused key with different content is rejected rather than silently
 * returning the wrong transaction.
 */
class TransactionCreateIdempotencyPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDateTime TRANSACTED_AT = LocalDateTime.of(2026, 9, 23, 12, 0);

    @Autowired
    private TransactionCommandService transactionCommandService;

    @Test
    void replayingTheSameKeyReturnsTheOriginalFactWithoutASecondRow() {
        Long userId = insertUser("idem-replay");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "EXPENSE");

        TransactionResponse first = transactionCommandService.create(userId, "key-replay",
                request(accountId, categoryId, "EXPENSE", "25.00", "lunch"));
        TransactionResponse replay = transactionCommandService.create(userId, "key-replay",
                request(accountId, categoryId, "EXPENSE", "25.00", "lunch"));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(transactionCount(userId)).isEqualTo(1);
        assertBalance(accountId, "75.00");
    }

    @Test
    void replayingTheSameKeyDoesNotApplyTheBalanceDeltaTwice() {
        Long userId = insertUser("idem-balance-once");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "INCOME");

        for (int attempt = 0; attempt < 3; attempt++) {
            transactionCommandService.create(userId, "key-balance", request(accountId, categoryId, "INCOME", "40.00", "salary"));
        }

        assertThat(transactionCount(userId)).isEqualTo(1);
        assertBalance(accountId, "140.00");
    }

    @Test
    void theSameKeyWithDifferentContentIsRejectedInsteadOfReplayingTheWrongFact() {
        Long userId = insertUser("idem-conflict");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "EXPENSE");

        transactionCommandService.create(userId, "key-conflict", request(accountId, categoryId, "EXPENSE", "25.00", "lunch"));

        assertThatThrownBy(() -> transactionCommandService.create(userId, "key-conflict",
                request(accountId, categoryId, "EXPENSE", "30.00", "lunch")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);

        assertThat(transactionCount(userId)).isEqualTo(1);
        assertBalance(accountId, "75.00");
    }

    @Test
    void concurrentRequestsWithTheSameKeyCreateOneFactAndApplyOneDelta() throws Exception {
        Long userId = insertUser("idem-concurrent");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "EXPENSE");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        try {
            List<Future<TransactionResponse>> futures = List.of(
                    executor.submit(() -> createAfterBarrier(startTogether, userId, accountId, categoryId)),
                    executor.submit(() -> createAfterBarrier(startTogether, userId, accountId, categoryId)));

            TransactionResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
            TransactionResponse second = futures.get(1).get(30, TimeUnit.SECONDS);

            assertThat(first.id()).isEqualTo(second.id());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(transactionCount(userId)).isEqualTo(1);
        assertBalance(accountId, "75.00");
    }

    private TransactionResponse createAfterBarrier(CyclicBarrier barrier, Long userId, Long accountId, Long categoryId) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return transactionCommandService.create(userId, "key-concurrent",
                    request(accountId, categoryId, "EXPENSE", "25.00", "lunch"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private TransactionRequest request(Long accountId, Long categoryId, String type, String amount, String description) {
        return new TransactionRequest(accountId, categoryId, type, new BigDecimal(amount), "CNY", description, TRANSACTED_AT);
    }

    private int transactionCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ?", Integer.class, userId);
    }

    private void assertBalance(Long accountId, String expected) {
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId))
                .isEqualByComparingTo(expected);
    }

    private Long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash) VALUES (?, ?, 'hash') RETURNING id
                """, Long.class, username, username + "@example.com");
    }

    private Long insertAccount(Long userId, String balance) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, 'Test', 'BANK', 'CNY', ?) RETURNING id
                """, Long.class, userId, new BigDecimal(balance));
    }

    private Long insertCategory(Long userId, String type) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO categories (user_id, name, type) VALUES (?, 'Test', ?) RETURNING id
                """, Long.class, userId, type);
    }
}
