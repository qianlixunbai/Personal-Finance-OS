package com.financeos.module.importing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@TableName("transaction_import_items")
public class TransactionImportItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private UUID batchId;
    private Integer sourceRowNumber;
    private String canonicalRowFingerprint;
    private String warningCodes;
    private Long createdTransactionId;
    private Instant createdAt;
}
