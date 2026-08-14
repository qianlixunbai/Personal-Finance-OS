package com.financeos.module.importing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financeos.module.importing.mapper.PostgresUuidTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.UUID;

@Data
@TableName("transaction_import_sessions")
public class TransactionImportSession {
    @TableId(type = IdType.INPUT)
    @TableField(typeHandler = PostgresUuidTypeHandler.class, jdbcType = JdbcType.OTHER)
    private UUID id;
    private Long userId;
    @TableField(typeHandler = PostgresUuidTypeHandler.class, jdbcType = JdbcType.OTHER)
    private UUID preallocatedBatchId;
    private String status;
    private Integer revision;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private String fileDigest;
    private String temporaryStorageReference;
    private String mappingDigest;
    private String optionsDigest;
    private String normalizedRowsDigest;
    private String planStorageReference;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant cancelledAt;
    private Instant createdAt;
    private Instant updatedAt;
}
