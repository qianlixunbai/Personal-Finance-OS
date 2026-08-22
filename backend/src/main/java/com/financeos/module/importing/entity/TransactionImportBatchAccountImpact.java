package com.financeos.module.importing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@TableName("transaction_import_batch_account_impacts")
public class TransactionImportBatchAccountImpact {
    @TableId(type = IdType.AUTO) private Long id;
    private Long userId;
    private UUID batchId;
    private Long accountId;
    private Integer rowCount;
    private BigDecimal balanceBefore;
    private BigDecimal delta;
    private BigDecimal balanceAfter;
    private Instant createdAt;
}
