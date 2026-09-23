package com.financeos.module.ledger.service;

import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.dto.TransactionResponse;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Non-transactional facade for ordinary transaction creation that carries an Idempotency-Key.
 *
 * <p>The unique-index race is resolved outside the failed transaction: when two requests share
 * one key, the loser rolls back and replays the winner's committed fact instead of surfacing a
 * conflict. This mirrors the pattern already used by {@code InvestmentCommandService}. The
 * server never retries the write itself — only the read of the authoritative result.
 */
@Service
public class TransactionCommandService {

    private static final String IDEMPOTENCY_INDEX = "uk_transactions_user_idempotency";

    private final TransactionService transactionService;

    public TransactionCommandService(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    public TransactionResponse create(Long userId, String idempotencyKey, TransactionRequest req) {
        try {
            return transactionService.create(userId, idempotencyKey, req);
        } catch (DataIntegrityViolationException exception) {
            if (idempotencyKey == null || !isIdempotencyUniqueViolation(exception)) {
                throw exception;
            }
            return transactionService.replayConcurrentCreate(userId, idempotencyKey, req);
        }
    }

    private boolean isIdempotencyUniqueViolation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof PSQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getServerErrorMessage() != null
                    && IDEMPOTENCY_INDEX.equals(sqlException.getServerErrorMessage().getConstraint())) {
                return true;
            }
        }
        return false;
    }
}
