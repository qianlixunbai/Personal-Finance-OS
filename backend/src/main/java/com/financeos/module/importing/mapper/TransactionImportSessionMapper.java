package com.financeos.module.importing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financeos.module.importing.entity.TransactionImportSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper
public interface TransactionImportSessionMapper extends BaseMapper<TransactionImportSession> {
    @Select("SELECT * FROM transaction_import_sessions WHERE id = #{id} AND user_id = #{userId}")
    TransactionImportSession findByIdAndUserId(@Param("id") UUID id, @Param("userId") Long userId);

    @Select("SELECT * FROM transaction_import_sessions WHERE id = #{id} AND user_id = #{userId} FOR UPDATE")
    TransactionImportSession findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") Long userId);

    @Update("""
            UPDATE transaction_import_sessions
            SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
              AND status IN ('MAPPING_REQUIRED', 'PREVIEW_READY') AND expires_at <= #{now}
            """)
    int markExpired(@Param("id") UUID id, @Param("userId") Long userId, @Param("now") Instant now);

    @Update("""
            UPDATE transaction_import_sessions
            SET status = 'CONSUMED', consumed_at = #{now}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND status = 'PREVIEW_READY' AND expires_at > #{now}
            """)
    int consumeReadySession(@Param("id") UUID id, @Param("userId") Long userId, @Param("now") Instant now);

    @Update("""
            UPDATE transaction_import_sessions
            SET status = 'PREVIEW_READY', revision = revision + 1,
                mapping_digest = #{mappingDigest}, normalized_rows_digest = #{normalizedRowsDigest},
                plan_storage_reference = #{planStorageReference}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
              AND status IN ('MAPPING_REQUIRED', 'PREVIEW_READY') AND expires_at > #{now}
            """)
    int markPreviewReady(@Param("id") UUID id, @Param("userId") Long userId,
                         @Param("mappingDigest") String mappingDigest,
                         @Param("normalizedRowsDigest") String normalizedRowsDigest,
                         @Param("planStorageReference") String planStorageReference,
                         @Param("now") Instant now);

    @Update("""
            UPDATE transaction_import_sessions
            SET status = 'MAPPING_REQUIRED', revision = revision + 1,
                mapping_digest = NULL, normalized_rows_digest = NULL, plan_storage_reference = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
              AND status IN ('MAPPING_REQUIRED', 'PREVIEW_READY') AND expires_at > #{now}
            """)
    int invalidatePreview(@Param("id") UUID id, @Param("userId") Long userId, @Param("now") Instant now);

    @Update("""
            UPDATE transaction_import_sessions
            SET status = 'CANCELLED', cancelled_at = #{now}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
              AND status IN ('MAPPING_REQUIRED', 'PREVIEW_READY') AND expires_at > #{now}
            """)
    int cancel(@Param("id") UUID id, @Param("userId") Long userId, @Param("now") Instant now);

    @Select("""
            SELECT * FROM transaction_import_sessions
            WHERE (temporary_storage_reference IS NOT NULL OR plan_storage_reference IS NOT NULL)
              AND ((expires_at <= #{now} AND status IN ('MAPPING_REQUIRED', 'PREVIEW_READY'))
                   OR status IN ('EXPIRED', 'CANCELLED', 'CONSUMED'))
            """)
    List<TransactionImportSession> findExpiredForCleanup(@Param("now") Instant now);

    @Update("""
            UPDATE transaction_import_sessions
            SET temporary_storage_reference = NULL, plan_storage_reference = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int clearCleanupReferences(@Param("id") UUID id, @Param("userId") Long userId);
}
