package com.financeos.module.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"rawtypes", "unchecked"})
class AccountQueryServiceTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Account.class);
    }

    @Test
    void sumBalanceByUserOnlyQueriesCnyAccounts() {
        AccountMapper accountMapper = mock(AccountMapper.class);
        AccountQueryService service = new AccountQueryService(accountMapper);
        when(accountMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        service.sumBalanceByUser(1L);

        ArgumentCaptor<LambdaQueryWrapper<Account>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(accountMapper).selectList(queryCaptor.capture());
        assertTrue(queryCaptor.getValue().getSqlSegment().contains("currency"));
    }
}
