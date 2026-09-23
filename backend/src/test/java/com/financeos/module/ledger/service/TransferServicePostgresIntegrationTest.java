package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.ledger.dto.TransferRequest;
import com.financeos.module.ledger.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferServicePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Test
    void createPersistsFactAndMovesBothBalancesAtomically() {
        Long userId = insertUser("transfer-valid");
        Long fromAccount = insertAccount(userId, "100.00");
        Long toAccount = insertAccount(userId, "250.00");

        TransferResponse response = transferService.create(userId, request(fromAccount, toAccount, "40.25"));

        assertBalance(fromAccount, "59.75");
        assertBalance(toAccount, "290.25");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT from_account_id FROM transfers WHERE id = ?", Long.class, response.id()))
                .isEqualTo(fromAccount);
        assertThat(jdbcTemplate.queryForObject("SELECT to_account_id FROM transfers WHERE id = ?", Long.class, response.id()))
                .isEqualTo(toAccount);
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM transfers WHERE id = ?", BigDecimal.class, response.id()))
                .isEqualByComparingTo("40.25");
        assertThat(jdbcTemplate.queryForObject("SELECT currency FROM transfers WHERE id = ?", String.class, response.id()))
                .isEqualTo("CNY");
    }

    @Test
    void transferMayDriveTheSourceAccountBelowZero() {
        Long userId = insertUser("transfer-negative-balance");
        Long fromAccount = insertAccount(userId, "5.00");
        Long toAccount = insertAccount(userId, "10.00");

        transferService.create(userId, request(fromAccount, toAccount, "12.50"));

        assertBalance(fromAccount, "-7.50");
        assertBalance(toAccount, "22.50");
        assertThat(transferCount(userId)).isEqualTo(1);
    }

    @Test
    void foreignOwnedAccountReturnsNotFoundWithoutChangingEitherUsersData() {
        Long ownerId = insertUser("transfer-owner");
        Long otherUserId = insertUser("transfer-other-user");
        Long ownerAccount = insertAccount(ownerId, "100.00");
        Long foreignAccount = insertAccount(otherUserId, "200.00");

        assertThatThrownBy(() -> transferService.create(ownerId, request(ownerAccount, foreignAccount, "25.00")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);

        assertBalance(ownerAccount, "100.00");
        assertBalance(foreignAccount, "200.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class)).isZero();
    }

    @Test
    void foreignSourceAccountReturnsNotFoundWithoutChangingEitherUsersData() {
        Long ownerId = insertUser("transfer-source-owner");
        Long otherUserId = insertUser("transfer-source-other-user");
        Long foreignAccount = insertAccount(otherUserId, "200.00");
        Long ownerAccount = insertAccount(ownerId, "100.00");

        assertThatThrownBy(() -> transferService.create(ownerId, request(foreignAccount, ownerAccount, "25.00")))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(404);

        assertBalance(ownerAccount, "100.00");
        assertBalance(foreignAccount, "200.00");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers", Integer.class)).isZero();
    }

    @Test
    void destinationBalanceNumericOverflowRollsBackTheTransferAndSourceDelta() {
        Long userId = insertUser("transfer-balance-overflow");
        Long fromAccount = insertAccount(userId, "100.00");
        Long toAccount = insertAccount(userId, "9999999999999999.99");

        assertThatThrownBy(() -> transferService.create(userId, request(fromAccount, toAccount, "1.00")))
                .isInstanceOf(RuntimeException.class);

        assertBalance(fromAccount, "100.00");
        assertBalance(toAccount, "9999999999999999.99");
        assertThat(transferCount(userId)).isZero();
    }

    @Test
    void oppositeDirectionTransfersCompleteWithoutDeadlockAndPreserveTotal() throws Exception {
        Long userId = insertUser("transfer-opposite-concurrency");
        Long firstAccount = insertAccount(userId, "100.00");
        Long secondAccount = insertAccount(userId, "200.00");

        runTogether(List.of(
                () -> transferService.create(userId, request(firstAccount, secondAccount, "30.00")),
                () -> transferService.create(userId, request(secondAccount, firstAccount, "50.00"))));

        assertBalance(firstAccount, "120.00");
        assertBalance(secondAccount, "180.00");
        assertThat(transferCount(userId)).isEqualTo(2);
        assertThat(balance(firstAccount).add(balance(secondAccount))).isEqualByComparingTo("300.00");
    }

    @Test
    void twentyConcurrentSameDirectionTransfersAreAllPersistedExactlyOnce() throws Exception {
        Long userId = insertUser("transfer-same-direction-concurrency");
        Long fromAccount = insertAccount(userId, "1000.00");
        Long toAccount = insertAccount(userId, "0.00");
        List<Callable<TransferResponse>> commands = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            commands.add(() -> transferService.create(userId, request(fromAccount, toAccount, "1.25")));
        }

        List<TransferResponse> responses = runTogether(commands);

        assertThat(responses).hasSize(20).extracting(TransferResponse::id).doesNotContainNull().doesNotHaveDuplicates();
        assertBalance(fromAccount, "975.00");
        assertBalance(toAccount, "25.00");
        assertThat(transferCount(userId)).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(amount) FROM transfers WHERE user_id = ?", BigDecimal.class, userId))
                .isEqualByComparingTo("25.00");
        assertThat(balance(fromAccount).add(balance(toAccount))).isEqualByComparingTo("1000.00");
    }

    private List<TransferResponse> runTogether(List<Callable<TransferResponse>> commands) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(commands.size());
        CyclicBarrier startTogether = new CyclicBarrier(commands.size());
        try {
            List<Future<TransferResponse>> futures = new ArrayList<>();
            for (Callable<TransferResponse> command : commands) {
                futures.add(executor.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return command.call();
                }));
            }
            List<TransferResponse> responses = new ArrayList<>();
            for (Future<TransferResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private TransferRequest request(Long fromAccountId, Long toAccountId, String amount) {
        return new TransferRequest(fromAccountId, toAccountId, new BigDecimal(amount),
                LocalDateTime.of(2026, 9, 23, 12, 0), "integration transfer");
    }

    private Long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash) VALUES (?, ?, 'hash') RETURNING id
                """, Long.class, username, username + "@example.com");
    }

    private Long insertAccount(Long userId, String balance) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, 'Transfer test', 'BANK', 'CNY', ?) RETURNING id
                """, Long.class, userId, new BigDecimal(balance));
    }

    private BigDecimal balance(Long accountId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
    }

    private void assertBalance(Long accountId, String expected) {
        assertThat(balance(accountId)).isEqualByComparingTo(expected);
    }

    private int transferCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM transfers WHERE user_id = ?", Integer.class, userId);
    }
}
