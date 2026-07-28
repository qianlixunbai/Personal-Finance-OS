package com.financeos.module.investment.command;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.investment.entity.InvestmentTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
class InvestmentWriteConsistencyChecker {
    void verify(InvestmentTransaction transaction, Asset asset, BigDecimal appliedBalanceAfter) {
        if (transaction == null
                || asset == null
                || !validDividend(transaction)
                || transaction.getAccountBalanceAfter() == null
                || appliedBalanceAfter == null
                || transaction.getAccountBalanceAfter().compareTo(appliedBalanceAfter) != 0
                || !same(transaction.getPositionQuantityAfter(), asset.getQuantity())
                || !same(transaction.getPositionAvgCostAfter(), asset.getAvgCost())
                || !same(transaction.getPositionTotalCostAfter(), asset.getTotalCost())
                || !same(transaction.getPositionRealizedProfitLossAfter(), asset.getRealizedProfitLoss())
                || !transaction.getPositionStatusAfter().equals(asset.getPositionStatus())
                || !transaction.getProjectionVersionAfter().equals(asset.getProjectionVersion())
                || !transaction.getId().equals(asset.getLastTransactionId())) {
            throw new InvestmentWriteConsistencyException("Investment write receipt does not match the final projection");
        }
    }

    private boolean validDividend(InvestmentTransaction transaction) {
        if (!"DIVIDEND".equals(transaction.getTransactionType())) {
            return true;
        }
        return "POSTED".equals(transaction.getStatus())
                && "MANUAL".equals(transaction.getSource())
                && "CNY".equals(transaction.getCurrency())
                && transaction.getQuantity() == null
                && transaction.getUnitPrice() == null
                && zero(transaction.getReleasedCostAmount())
                && zero(transaction.getRealizedProfitLoss())
                && transaction.getGrossAmount() != null
                && transaction.getFeeAmount() != null
                && transaction.getTaxAmount() != null
                && transaction.getNetAmount() != null
                && transaction.getGrossAmount().subtract(transaction.getFeeAmount()).subtract(transaction.getTaxAmount())
                .compareTo(transaction.getNetAmount()) == 0
                && transaction.getTradeTime() != null
                && transaction.getTradeTime().equals(transaction.getSettlementTime())
                && transaction.getReplacesTransactionId() == null
                && transaction.getReversedAt() == null
                && transaction.getReversalReason() == null
                && transaction.getAccountBalanceAfter() != null
                && transaction.getPositionQuantityAfter() != null
                && transaction.getPositionAvgCostAfter() != null
                && transaction.getPositionTotalCostAfter() != null
                && transaction.getPositionRealizedProfitLossAfter() != null
                && transaction.getPositionStatusAfter() != null
                && transaction.getProjectionVersionAfter() != null;
    }

    private boolean zero(BigDecimal value) {
        return value != null && value.signum() == 0;
    }

    private boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}
