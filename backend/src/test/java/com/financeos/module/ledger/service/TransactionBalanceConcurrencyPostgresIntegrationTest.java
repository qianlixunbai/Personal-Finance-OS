package com.financeos.module.ledger.service;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.common.BusinessException;
import com.financeos.module.account.dto.AccountRequest;
import com.financeos.module.account.service.AccountService;
import com.financeos.module.ledger.dto.TransactionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionBalanceConcurrencyPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentCreatesAgainstTheSameAccountPreserveBothDeltas() throws Exception {
        Long userId = insertUser("concurrent-create");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "INCOME");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        try {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> createAfterBarrier(startTogether, userId, accountId, categoryId, "10.00")),
                    executor.submit(() -> createAfterBarrier(startTogether, userId, accountId, categoryId, "20.00")));

            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId))
                .isEqualByComparingTo("130.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ?", Integer.class, accountId))
                .isEqualTo(2);
    }

    @Test
    void concurrentDeletesReverseTheOriginalEffectOnlyOnce() throws Exception {
        Long userId = insertUser("concurrent-delete");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "INCOME");
        Long transactionId = create(userId, accountId, categoryId, "INCOME", "30.00");

        List<Object> results = runTogether(
                () -> deleteResult(userId, transactionId), () -> deleteResult(userId, transactionId));

        assertThat(results).containsExactlyInAnyOrder("deleted", 404);
        assertBalance(accountId, "100.00");
        assertThat(transactionCount(accountId)).isZero();
    }

    @Test
    void concurrentUpdatesUseLatestLockedFactAndLeaveBalanceEqualToFinalFact() throws Exception {
        Long userId = insertUser("concurrent-update");
        Long accountId = insertAccount(userId, "100.00");
        Long incomeCategory = insertCategory(userId, "INCOME");
        Long expenseCategory = insertCategory(userId, "EXPENSE");
        Long transactionId = create(userId, accountId, incomeCategory, "INCOME", "10.00");

        runTogether(
                () -> { update(userId, transactionId, accountId, expenseCategory, "EXPENSE", "30.00"); return "A"; },
                () -> { update(userId, transactionId, accountId, incomeCategory, "INCOME", "40.00"); return "B"; });

        BigDecimal finalAmount = jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, transactionId);
        String finalType = jdbcTemplate.queryForObject("SELECT type FROM transactions WHERE id = ?", String.class, transactionId);
        assertThat(finalType).isIn("INCOME", "EXPENSE");
        assertBalance(accountId, "INCOME".equals(finalType) ? "140.00" : "70.00");
        assertThat(finalAmount).isIn(new BigDecimal("40.00"), new BigDecimal("30.00"));
    }

    @Test
    void crossAccountUpdateReversesOldEffectAndAppliesNewEffect() {
        Long userId = insertUser("cross-account");
        Long oldAccount = insertAccount(userId, "100.00");
        Long newAccount = insertAccount(userId, "200.00");
        Long incomeCategory = insertCategory(userId, "INCOME");
        Long expenseCategory = insertCategory(userId, "EXPENSE");
        Long transactionId = create(userId, oldAccount, incomeCategory, "INCOME", "30.00");

        update(userId, transactionId, newAccount, expenseCategory, "EXPENSE", "40.00");

        assertBalance(oldAccount, "100.00");
        assertBalance(newAccount, "160.00");
        assertThat(jdbcTemplate.queryForObject("SELECT account_id FROM transactions WHERE id = ?", Long.class, transactionId))
                .isEqualTo(newAccount);
    }

    @Test
    void crossingAccountSwapsCompleteWithoutReverseOrderDeadlock() throws Exception {
        Long userId = insertUser("crossing-swaps");
        Long firstAccount = insertAccount(userId, "100.00");
        Long secondAccount = insertAccount(userId, "200.00");
        Long incomeCategory = insertCategory(userId, "INCOME");
        Long expenseCategory = insertCategory(userId, "EXPENSE");
        Long firstTransaction = create(userId, firstAccount, incomeCategory, "INCOME", "10.00");
        Long secondTransaction = create(userId, secondAccount, incomeCategory, "INCOME", "20.00");

        runTogether(
                () -> { update(userId, firstTransaction, secondAccount, expenseCategory, "EXPENSE", "30.00"); return "first"; },
                () -> { update(userId, secondTransaction, firstAccount, expenseCategory, "EXPENSE", "40.00"); return "second"; });

        assertBalance(firstAccount, "60.00");
        assertBalance(secondAccount, "170.00");
        assertThat(jdbcTemplate.queryForObject("SELECT account_id FROM transactions WHERE id = ?", Long.class, firstTransaction))
                .isEqualTo(secondAccount);
        assertThat(jdbcTemplate.queryForObject("SELECT account_id FROM transactions WHERE id = ?", Long.class, secondTransaction))
                .isEqualTo(firstAccount);
    }

    @Test
    void crossUserTransactionUpdateReturnsNotFoundWithoutChangingAnyBalance() {
        Long owner = insertUser("owner-isolation");
        Long intruder = insertUser("intruder-isolation");
        Long ownerAccount = insertAccount(owner, "100.00");
        Long intruderAccount = insertAccount(intruder, "200.00");
        Long ownerCategory = insertCategory(owner, "INCOME");
        Long intruderCategory = insertCategory(intruder, "INCOME");
        Long transactionId = create(owner, ownerAccount, ownerCategory, "INCOME", "30.00");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> update(intruder, transactionId, intruderAccount,
                intruderCategory, "INCOME", "40.00"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);

        assertBalance(ownerAccount, "130.00");
        assertBalance(intruderAccount, "200.00");
    }

    @Test
    void metadataAndCreateOnSameAccountPreserveBothBalanceAndMetadata() throws Exception {
        Long userId = insertUser("metadata-create");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "INCOME");

        runTogether(
                () -> { accountService.update(userId, accountId, new AccountRequest("Renamed", "CASH", "CNY")); return "metadata"; },
                () -> { create(userId, accountId, categoryId, "INCOME", "20.00"); return "create"; });

        assertThat(jdbcTemplate.queryForObject("SELECT name FROM accounts WHERE id = ?", String.class, accountId)).isEqualTo("Renamed");
        assertBalance(accountId, "120.00");
        assertThat(transactionCount(accountId)).isEqualTo(1);
    }

    @Test
    void deactivateBeforeCreateRejectsNewTransactionAndDoesNotChangeBalance() {
        Long userId = insertUser("deactivate-create");
        Long accountId = insertAccount(userId, "100.00");
        Long categoryId = insertCategory(userId, "INCOME");

        accountService.deactivate(userId, accountId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> create(userId, accountId, categoryId, "INCOME", "20.00"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertBalance(accountId, "100.00");
        assertThat(transactionCount(accountId)).isZero();
    }

    @Test
    void updateThenDelete_reversesLatestUpdatedEffect() throws Exception {
        Long userId = insertUser("update-then-delete");
        Long accountId = insertAccount(userId, "100.00");
        Long expenseCategoryId = insertCategory(userId, "EXPENSE");
        Long transactionId = create(userId, accountId, expenseCategoryId, "EXPENSE", "20.00");
        CountDownLatch updateReadyToCommit = new CountDownLatch(1);
        CountDownLatch releaseUpdate = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> updateFuture = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                update(userId, transactionId, accountId, expenseCategoryId, "EXPENSE", "50.00");
                updateReadyToCommit.countDown();
                awaitLatch(releaseUpdate);
            }));

            assertThat(updateReadyToCommit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> deleteFuture = executor.submit(() -> transactionService.delete(userId, transactionId));
            awaitDatabaseLockWait();
            releaseUpdate.countDown();

            updateFuture.get(10, TimeUnit.SECONDS);
            deleteFuture.get(10, TimeUnit.SECONDS);
        } finally {
            releaseUpdate.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(transactionCount(accountId)).isZero();
        assertBalance(accountId, "100.00");
    }

    @Test
    void deleteThenUpdate_returnsNotFoundWithoutSecondReversal() throws Exception {
        Long userId = insertUser("delete-then-update");
        Long accountId = insertAccount(userId, "100.00");
        Long expenseCategoryId = insertCategory(userId, "EXPENSE");
        Long transactionId = create(userId, accountId, expenseCategoryId, "EXPENSE", "20.00");
        CountDownLatch deleteReadyToCommit = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deleteFuture = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                transactionService.delete(userId, transactionId);
                deleteReadyToCommit.countDown();
                awaitLatch(releaseDelete);
            }));

            assertThat(deleteReadyToCommit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Object> updateFuture = executor.submit(() -> updateResult(
                    userId, transactionId, accountId, expenseCategoryId, "EXPENSE", "50.00"));
            awaitDatabaseLockWait();
            releaseDelete.countDown();

            deleteFuture.get(10, TimeUnit.SECONDS);
            assertThat(updateFuture.get(10, TimeUnit.SECONDS)).isEqualTo(404);
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(transactionCount(accountId)).isZero();
        assertBalance(accountId, "100.00");
    }

    @Test
    void createRollsBackTransactionWhenBalanceUpdateFails() {
        Long userId = insertUser("create-rollback");
        Long accountId = insertAccount(userId, "100.00");
        Long incomeCategoryId = insertCategory(userId, "INCOME");

        assertBalanceUpdateFailureRollsBack(accountId,
                () -> create(userId, accountId, incomeCategoryId, "INCOME", "20.00"));

        assertThat(transactionCount(accountId)).isZero();
        assertBalance(accountId, "100.00");
    }

    @Test
    void updateRollsBackFactWhenBalanceMutationFails() {
        Long userId = insertUser("update-rollback");
        Long accountId = insertAccount(userId, "100.00");
        Long expenseCategoryId = insertCategory(userId, "EXPENSE");
        Long transactionId = create(userId, accountId, expenseCategoryId, "EXPENSE", "20.00");

        assertBalanceUpdateFailureRollsBack(accountId,
                () -> update(userId, transactionId, accountId, expenseCategoryId, "EXPENSE", "50.00"));

        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, transactionId))
                .isEqualByComparingTo("20.00");
        assertThat(jdbcTemplate.queryForObject("SELECT type FROM transactions WHERE id = ?", String.class, transactionId))
                .isEqualTo("EXPENSE");
        assertBalance(accountId, "80.00");
    }

    @Test
    void deleteRollsBackFactWhenBalanceReversalFails() {
        Long userId = insertUser("delete-rollback");
        Long accountId = insertAccount(userId, "100.00");
        Long expenseCategoryId = insertCategory(userId, "EXPENSE");
        Long transactionId = create(userId, accountId, expenseCategoryId, "EXPENSE", "20.00");

        assertBalanceUpdateFailureRollsBack(accountId, () -> transactionService.delete(userId, transactionId));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE id = ?", Integer.class, transactionId))
                .isEqualTo(1);
        assertBalance(accountId, "80.00");
    }

    private void createAfterBarrier(CyclicBarrier barrier, Long userId, Long accountId, Long categoryId, String amount) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            transactionService.create(userId, new TransactionRequest(accountId, categoryId, "INCOME",
                    new BigDecimal(amount), "CNY", "concurrent", LocalDateTime.of(2026, 7, 23, 12, 0)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long create(Long userId, Long accountId, Long categoryId, String type, String amount) {
        return transactionService.create(userId, new TransactionRequest(accountId, categoryId, type,
                new BigDecimal(amount), "CNY", "test", LocalDateTime.of(2026, 7, 23, 12, 0))).id();
    }

    private void update(Long userId, Long transactionId, Long accountId, Long categoryId, String type, String amount) {
        transactionService.update(userId, transactionId, new TransactionRequest(accountId, categoryId, type,
                new BigDecimal(amount), "CNY", "update", LocalDateTime.of(2026, 7, 23, 12, 0)));
    }

    private Object deleteResult(Long userId, Long transactionId) {
        try {
            transactionService.delete(userId, transactionId);
            return "deleted";
        } catch (BusinessException exception) {
            return exception.getCode();
        }
    }

    private Object updateResult(Long userId, Long transactionId, Long accountId, Long categoryId, String type, String amount) {
        try {
            update(userId, transactionId, accountId, categoryId, type, amount);
            return "updated";
        } catch (BusinessException exception) {
            return exception.getCode();
        }
    }

    private void assertBalanceUpdateFailureRollsBack(Long accountId, Runnable command) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String functionName = "test_balance_failure_fn_" + suffix;
        String triggerName = "test_balance_failure_tr_" + suffix;
        try {
            jdbcTemplate.execute("""
                    CREATE FUNCTION %s() RETURNS trigger AS $$
                    BEGIN
                        IF NEW.id = %d THEN
                            RAISE EXCEPTION 'forced account balance update failure';
                        END IF;
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """.formatted(functionName, accountId));
            jdbcTemplate.execute("""
                    CREATE TRIGGER %s
                    BEFORE UPDATE OF balance ON accounts
                    FOR EACH ROW
                    WHEN (OLD.balance IS DISTINCT FROM NEW.balance)
                    EXECUTE FUNCTION %s()
                    """.formatted(triggerName, functionName));

            org.assertj.core.api.Assertions.assertThatThrownBy(command::run)
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON accounts");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_trigger WHERE tgname = ?", Integer.class, triggerName))
                .isZero();
    }

    private void awaitDatabaseLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pg_locks WHERE NOT granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("second transaction did not enter a PostgreSQL lock wait");
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release the controlled transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private List<Object> runTogether(java.util.concurrent.Callable<Object> first,
                                     java.util.concurrent.Callable<Object> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<Object> firstFuture = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            Future<Object> secondFuture = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(firstFuture.get(10, TimeUnit.SECONDS), secondFuture.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void assertBalance(Long accountId, String expected) {
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId))
                .isEqualByComparingTo(expected);
    }

    private int transactionCount(Long accountId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ?", Integer.class, accountId);
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
