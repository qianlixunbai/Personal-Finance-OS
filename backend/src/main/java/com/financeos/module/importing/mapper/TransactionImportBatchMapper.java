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

    @Select("SELECT * FROM transaction_import_batches WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} AND status = 'CONFIRMED'")
    TransactionImportBatch findConfirmedByUserIdAndIdempotencyKey(@Param("userId") Long userId,
                                                                    @Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM transaction_import_batches WHERE user_id = #{userId} AND session_id = #{sessionId} AND status = 'CONFIRMED'")
    TransactionImportBatch findConfirmedByUserIdAndSessionId(@Param("userId") Long userId, @Param("sessionId") UUID sessionId);

    @Select("""
            SELECT * FROM transaction_import_batches
            WHERE user_id = #{userId} AND file_digest = #{fileDigest} AND mapping_digest = #{mappingDigest}
              AND options_digest = #{optionsDigest} AND normalized_rows_digest = #{normalizedRowsDigest}
              AND status = 'CONFIRMED'
            """)
    TransactionImportBatch findConfirmedExactDuplicate(@Param("userId") Long userId,
                                                        @Param("fileDigest") String fileDigest,
                                                        @Param("mappingDigest") String mappingDigest,
                                                        @Param("optionsDigest") String optionsDigest,
                                                        @Param("normalizedRowsDigest") String normalizedRowsDigest);
}
