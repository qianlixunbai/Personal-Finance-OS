package com.financeos.module.investment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable, completed replacement command envelope. No write workflow is introduced in 5B-1. */
@Data
@TableName("investment_transaction_corrections")
public class InvestmentTransactionCorrection {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("correction_group_id")
    private UUID correctionGroupId;
    @TableField("user_id")
    private Long userId;
    @TableField("account_id")
    private Long accountId;
    @TableField("asset_id")
    private Long assetId;
    @TableField("instrument_id")
    private Long instrumentId;
    @TableField("original_transaction_id")
    private Long originalTransactionId;
    @TableField("transaction_type")
    private String transactionType;
    @TableField("correction_kind")
    private String correctionKind;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("request_hash")
    private String requestHash;
    @TableField("correction_reason")
    private String correctionReason;
    @TableField("reversal_transaction_id")
    private Long reversalTransactionId;
    @TableField("replacement_transaction_id")
    private Long replacementTransactionId;
    @TableField("reversal_cash_delta")
    private BigDecimal reversalCashDelta;
    @TableField("replacement_cash_delta")
    private BigDecimal replacementCashDelta;
    @TableField("command_cash_delta")
    private BigDecimal commandCashDelta;
    @TableField("balance_after")
    private BigDecimal balanceAfter;
    @TableField("position_quantity_after")
    private BigDecimal positionQuantityAfter;
    @TableField("position_avg_cost_after")
    private BigDecimal positionAvgCostAfter;
    @TableField("position_total_cost_after")
    private BigDecimal positionTotalCostAfter;
    @TableField("position_realized_profit_loss_after")
    private BigDecimal positionRealizedProfitLossAfter;
    @TableField("position_status_after")
    private String positionStatusAfter;
    @TableField("projection_version")
    private Integer projectionVersion;
    @TableField("last_transaction_id")
    private Long lastTransactionId;
    @TableField("created_at")
    private Instant createdAt;
}
