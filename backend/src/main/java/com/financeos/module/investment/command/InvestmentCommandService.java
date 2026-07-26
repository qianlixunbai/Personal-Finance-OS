package com.financeos.module.investment.command;

import com.financeos.module.investment.command.dto.FirstBuyRequest;
import com.financeos.module.investment.command.dto.InvestmentCommandResponse;
import com.financeos.module.investment.command.dto.InvestmentTradeRequest;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.springframework.dao.DataIntegrityViolationException;
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

    private InvestmentCommandResponse execute(Long userId, String idempotencyKey, Command command) {
        try {
            return command.execute();
        } catch (DataIntegrityViolationException exception) {
            InvestmentTransaction concurrent = transactionMapper.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (concurrent != null) {
                return command.execute();
            }
            throw exception;
        }
    }

    @FunctionalInterface
    private interface Command {
        InvestmentCommandResponse execute();
    }
}
