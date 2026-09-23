package com.financeos.module.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.ledger.entity.Transfer;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransferMapper extends BaseMapper<Transfer> {
}
