package com.financeos.module.investment.ledger;

import java.math.BigDecimal;

public record InvestmentLedgerCommand(
        InvestmentTransactionType transactionType,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal taxAmount) {

    public static InvestmentLedgerCommand buy(BigDecimal quantity, BigDecimal unitPrice, BigDecimal feeAmount, BigDecimal taxAmount) {
        return new InvestmentLedgerCommand(InvestmentTransactionType.BUY, quantity, unitPrice, null, feeAmount, taxAmount);
    }

    public static InvestmentLedgerCommand sell(BigDecimal quantity, BigDecimal unitPrice, BigDecimal feeAmount, BigDecimal taxAmount) {
        return new InvestmentLedgerCommand(InvestmentTransactionType.SELL, quantity, unitPrice, null, feeAmount, taxAmount);
    }

    public static InvestmentLedgerCommand dividend(BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal taxAmount) {
        return new InvestmentLedgerCommand(InvestmentTransactionType.DIVIDEND, null, null, grossAmount, feeAmount, taxAmount);
    }

    public static InvestmentLedgerCommand openingPosition(BigDecimal quantity, BigDecimal unitCost) {
        return new InvestmentLedgerCommand(InvestmentTransactionType.OPENING_POSITION, quantity, unitCost, null,
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }

    public static InvestmentLedgerCommand reversal() {
        return new InvestmentLedgerCommand(InvestmentTransactionType.REVERSAL, null, null, null, null, null);
    }
}
