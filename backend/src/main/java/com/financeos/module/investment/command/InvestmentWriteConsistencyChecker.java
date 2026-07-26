package com.financeos.module.investment.command;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.investment.entity.InvestmentTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
class InvestmentWriteConsistencyChecker {
    void verify(InvestmentTransaction transaction, Asset asset, BigDecimal appliedBalanceAfter) {
        if (transaction.getAccountBalanceAfter().compareTo(appliedBalanceAfter) != 0
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

    private boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}
