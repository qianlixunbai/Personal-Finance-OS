package com.financeos.module.investment.migration;

import com.financeos.module.account.entity.Account;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.ledger.InvestmentPositionState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
class LegacyMigrationConsistencyChecker {
    void verify(LegacyAssetMigrationPreflight preflight, InvestmentTransaction transaction, Asset asset,
                InvestmentPositionState replayedState, BigDecimal balanceBefore, Account accountAfter) {
        if (preflight == null || transaction == null || asset == null || replayedState == null || accountAfter == null || balanceBefore == null
                || asset.getQuantity() == null || asset.getTotalCost() == null || asset.getAvgCost() == null
                || asset.getRealizedProfitLoss() == null || accountAfter.getBalance() == null) {
            throw new LegacyMigrationConsistencyException("migration consistency data is incomplete");
        }
        if (!"OPENING_POSITION".equals(transaction.getTransactionType()) || !"POSTED".equals(transaction.getStatus())
                || transaction.getQuantity().compareTo(preflight.asset().getQuantity()) != 0
                || transaction.getGrossAmount().compareTo(preflight.cost().totalCost()) != 0
                || transaction.getUnitPrice().compareTo(preflight.cost().unitPrice()) != 0
                || transaction.getFeeAmount().signum() != 0 || transaction.getTaxAmount().signum() != 0
                || transaction.getNetAmount().signum() != 0 || transaction.getReleasedCostAmount().signum() != 0
                || transaction.getRealizedProfitLoss().signum() != 0 || !"CNY".equals(transaction.getCurrency())
                || !"MIGRATION".equals(transaction.getSource())
                || !("LEGACY_ASSET_OPENING_V1:" + asset.getId()).equals(transaction.getExternalReference())
                || !asset.getId().equals(transaction.getAssetId()) || !asset.getAccountId().equals(transaction.getAccountId())
                || asset.getQuantity().compareTo(replayedState.quantity()) != 0
                || asset.getTotalCost().compareTo(replayedState.totalCost()) != 0
                || asset.getRealizedProfitLoss().compareTo(replayedState.cumulativeRealizedProfitLoss()) != 0
                || asset.getAvgCost().compareTo(replayedState.totalCost().divide(replayedState.quantity(), 8,
                java.math.RoundingMode.HALF_UP)) != 0
                || !"OPEN".equals(asset.getPositionStatus()) || !"TRANSACTION_DRIVEN".equals(asset.getPositionMode())
                || !asset.getLastTransactionId().equals(transaction.getId()) || asset.getInstrumentId() == null
                || asset.getProjectionVersion() == null || asset.getProjectionVersion() < 1
                || accountAfter.getBalance().compareTo(balanceBefore) != 0) {
            throw new LegacyMigrationConsistencyException("migration consistency check failed");
        }
    }
}
