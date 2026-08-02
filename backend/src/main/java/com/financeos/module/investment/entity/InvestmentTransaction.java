package com.financeos.module.investment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@TableName("investment_transactions")
public class InvestmentTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("asset_id")
    private Long assetId;

    @TableField("account_id")
    private Long accountId;

    @TableField("transaction_type")
    private String transactionType;

    private String status;

    private BigDecimal quantity;

    @TableField("unit_price")
    private BigDecimal unitPrice;

    @TableField("gross_amount")
    private BigDecimal grossAmount;

    @TableField("fee_amount")
    private BigDecimal feeAmount;

    @TableField("tax_amount")
    private BigDecimal taxAmount;

    @TableField("net_amount")
    private BigDecimal netAmount;

    @TableField("released_cost_amount")
    private BigDecimal releasedCostAmount;

    @TableField("realized_profit_loss")
    private BigDecimal realizedProfitLoss;

    private String currency;

    @TableField("trade_time")
    private Instant tradeTime;

    @TableField("settlement_time")
    private Instant settlementTime;

    private String note;

    @TableField("external_reference")
    private String externalReference;

    private String source;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("request_hash")
    private String requestHash;

    @TableField("account_balance_after")
    private BigDecimal accountBalanceAfter;

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

    @TableField("projection_version_after")
    private Integer projectionVersionAfter;

    @TableField("replaces_transaction_id")
    private Long replacesTransactionId;

    @TableField("reversed_at")
    private Instant reversedAt;

    @TableField("reversal_reason")
    private String reversalReason;

    @TableField("original_transaction_id")
    private Long originalTransactionId;

    @TableField("correction_reason")
    private String correctionReason;

    @TableField("cash_delta")
    private BigDecimal cashDelta;

    @TableField("correction_group_id")
    private UUID correctionGroupId;

    @TableField("replay_anchor_transaction_id")
    private Long replayAnchorTransactionId;

    @TableField("replay_sequence")
    private Short replaySequence;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
