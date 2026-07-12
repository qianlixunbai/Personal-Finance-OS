package com.financeos.module.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
class TransactionQueryServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Transaction.class);
    }

    @Test
    void listRecentByUserOnlyQueriesCnyTransactions() {
        TransactionMapper transactionMapper = mock(TransactionMapper.class);
        TransactionQueryService service = new TransactionQueryService(transactionMapper);
        when(transactionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.listRecentByUser(1L, 5);

        ArgumentCaptor<LambdaQueryWrapper<Transaction>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(transactionMapper).selectList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("currency"));
    }

    @Test
    void monthlyCashFlowByMonthForwardsTheUserAndHalfOpenTimeRange() {
        TransactionMapper transactionMapper = mock(TransactionMapper.class);
        TransactionQueryService service = new TransactionQueryService(transactionMapper);
        LocalDateTime startInclusive = LocalDateTime.of(2025, 10, 1, 0, 0);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 3, 15, 12, 30);
        MonthlyCashFlowAggregate aggregate = new MonthlyCashFlowAggregate(
                LocalDate.of(2025, 10, 1), new BigDecimal("600.00"), new BigDecimal("150.00"));

        when(transactionMapper.monthlyCashFlowByMonth(7L, startInclusive, endExclusive)).thenReturn(List.of(aggregate));

        List<MonthlyCashFlowAggregate> result = service.monthlyCashFlowByMonth(7L, startInclusive, endExclusive);

        assertThat(result).containsExactly(aggregate);
        verify(transactionMapper).monthlyCashFlowByMonth(7L, startInclusive, endExclusive);
    }

    @Test
    void monthlyCashFlowByMonthReturnsAnEmptyListWhenMapperHasNoRows() {
        TransactionMapper transactionMapper = mock(TransactionMapper.class);
        TransactionQueryService service = new TransactionQueryService(transactionMapper);
        LocalDateTime startInclusive = LocalDateTime.of(2025, 10, 1, 0, 0);
        LocalDateTime endExclusive = LocalDateTime.of(2026, 3, 15, 12, 30);

        when(transactionMapper.monthlyCashFlowByMonth(7L, startInclusive, endExclusive)).thenReturn(List.of());

        assertThat(service.monthlyCashFlowByMonth(7L, startInclusive, endExclusive)).isEmpty();
    }
}
