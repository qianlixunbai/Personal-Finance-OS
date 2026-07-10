package com.financeos.module.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.financeos.module.ledger.entity.Transaction;
import com.financeos.module.ledger.mapper.TransactionMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void sumByTypeAndDateSqlOnlyAggregatesCnyTransactions() throws NoSuchMethodException {
        Select select = TransactionMapper.class
                .getMethod("sumByTypeAndDate", Long.class, String.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Select.class);

        assertTrue(String.join(" ", select.value()).contains("currency = 'CNY'"));
    }
}
