package com.financeos.module.importing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.importing.entity.TransactionImportItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransactionImportItemMapper extends BaseMapper<TransactionImportItem> {
}
