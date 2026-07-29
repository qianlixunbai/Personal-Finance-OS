package com.financeos.module.investment.command;

import com.financeos.module.investment.command.dto.FirstBuyRequest;
import com.financeos.module.investment.command.dto.InvestmentCommandResponse;
import com.financeos.module.investment.command.dto.InvestmentDividendRequest;
import com.financeos.module.investment.command.dto.InvestmentReversalRequest;
import com.financeos.module.investment.command.dto.InvestmentReversalResponse;
import com.financeos.module.investment.command.dto.InvestmentTradeRequest;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import com.financeos.common.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class InvestmentCommandService {
    private final InvestmentCommandTransactionalService transactionalService;
    private final InvestmentTransactionMapper transactionMapper;

    public InvestmentCommandService(InvestmentCommandTransactionalService transactionalService,
                                    InvestmentTransactionMapper transactionMapper) {
        this.transactionalService = transactionalService;
        this.transactionMapper = transactionMapper;
    }

    public InvestmentCommandResponse firstBuy(Long userId, String idempotencyKey, FirstBuyRequest request) {
        return execute(userId, idempotencyKey,
                () -> transactionalService.firstBuy(userId, idempotencyKey, request));
    }

    public InvestmentCommandResponse buy(Long userId, Long assetId, String idempotencyKey, InvestmentTradeRequest request) {
        return execute(userId, idempotencyKey,
                () -> transactionalService.trade(userId, assetId, idempotencyKey, request, "BUY"));
    }

    public InvestmentCommandResponse sell(Long userId, Long assetId, String idempotencyKey, InvestmentTradeRequest request) {
        return execute(userId, idempotencyKey,
                () -> transactionalService.trade(userId, assetId, idempotencyKey, request, "SELL"));
    }

    public InvestmentCommandResponse dividend(Long userId, Long assetId, String idempotencyKey, InvestmentDividendRequest request) {
        return execute(userId, idempotencyKey,
                () -> transactionalService.dividend(userId, assetId, idempotencyKey, request));
    }

    public InvestmentReversalResponse reverse(Long userId, Long transactionId, String idempotencyKey, InvestmentReversalRequest request) {
        try {
            return execute(userId, idempotencyKey,
                    () -> transactionalService.reverse(userId, transactionId, idempotencyKey, request));
        } catch (DataIntegrityViolationException exception) {
            if (isReversalOriginalUniqueViolation(exception)) {
                throw new BusinessException(409, "Investment transaction was already reversed");
            }
            throw new InvestmentReversalConsistencyException("Investment reversal persistence failed", exception);
        }
    }

    private <T> T execute(Long userId, String idempotencyKey, Command<T> command) {
        try {
            return command.execute();
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyUniqueViolation(exception)) {
                throw exception;
            }
            InvestmentTransaction concurrent = transactionMapper.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (concurrent != null) {
                return command.execute();
            }
            throw exception;
        }
    }

    private boolean isIdempotencyUniqueViolation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof PSQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getServerErrorMessage() != null
                    && "uk_investment_transactions_user_idempotency".equals(sqlException.getServerErrorMessage().getConstraint())) {
                return true;
            }
        }
        return false;
    }

    private boolean isReversalOriginalUniqueViolation(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof PSQLException sqlException
                    && "23505".equals(sqlException.getSQLState())
                    && sqlException.getServerErrorMessage() != null
                    && "uk_investment_transactions_reversal_original".equals(sqlException.getServerErrorMessage().getConstraint())) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface Command<T> {
        T execute();
    }
}
