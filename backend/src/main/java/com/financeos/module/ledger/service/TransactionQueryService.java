package com.financeos.module.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TransactionQueryService {

    private final TransactionMapper transactionMapper;

    public TransactionQueryService(TransactionMapper transactionMapper) {
        this.transactionMapper = transactionMapper;
    }

    public BigDecimal sumByTypeAndDate(Long userId, String type, LocalDateTime start, LocalDateTime end) {
        BigDecimal sum = transactionMapper.sumByTypeAndDate(userId, type, start, end);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    public List<Transaction> listRecentByUser(Long userId, int limit) {
        return transactionMapper.selectList(
                new LambdaQueryWrapper<Transaction>()
                        .eq(Transaction::getUserId, userId)
                        .orderByDesc(Transaction::getTransactedAt)
                        .last("LIMIT " + limit)
        );
    }
}
