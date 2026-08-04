package com.financeos.module.investment.read.mapper;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class InvestmentReadRow {
    private Long positionId;
    private Long accountId;
    private String accountName;
    private Long instrumentId;
    private String instrumentSymbol;
    private String instrumentName;
    private String instrumentMarket;
    private String instrumentAssetClass;
    private String instrumentQuoteCurrency;
    private String positionMode;
    private String positionStatus;
    private BigDecimal quantity;
    private BigDecimal averageCost;
    private BigDecimal totalCost;
    private BigDecimal cumulativeRealizedProfitLoss;
    private Long logicalTransactionId;
    private String transactionType;
    private Instant effectiveTradeTime;
    private BigDecimal unitPrice;
    private BigDecimal grossAmount;
    private BigDecimal feeAmount;
    private BigDecimal taxAmount;
    private BigDecimal netAmount;
    private BigDecimal releasedCostAmount;
    private BigDecimal realizedProfitLoss;
    private String correctionStatus;
    private Boolean effective;
    private Instant correctionCreatedAt;
    private String note;
    private String externalReference;
}
