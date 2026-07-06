package com.financeos.module.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.account.entity.Account;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {
}
