package com.financeos.module.importing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.importing.entity.TransactionImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface TransactionImportBatchMapper extends BaseMapper<TransactionImportBatch> {
    @Select("SELECT * FROM transaction_import_batches WHERE id = #{id} AND user_id = #{userId} AND status = 'CONFIRMED'")
    TransactionImportBatch findConfirmedByIdAndUserId(@Param("id") UUID id, @Param("userId") Long userId);
}
