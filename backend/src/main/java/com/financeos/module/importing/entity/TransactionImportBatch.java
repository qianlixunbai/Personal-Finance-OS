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
@TableName("transaction_import_batches")
public class TransactionImportBatch {
    @TableId(type = IdType.INPUT)
    @TableField(typeHandler = PostgresUuidTypeHandler.class, jdbcType = JdbcType.OTHER)
    private UUID id;
    private Long userId;
    private String originalFileName;
    private String fileDigest;
    private String mappingDigest;
    private String optionsDigest;
    private String normalizedRowsDigest;
    private String contractVersion;
    private String idempotencyKey;
    private String requestHash;
    private String status;
    private Integer totalRows;
    private Integer warningCount;
    private Instant confirmedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
