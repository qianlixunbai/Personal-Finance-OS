package com.financeos.module.investment.migration;

import com.financeos.module.account.entity.Account;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;

import java.util.List;

record LegacyAssetMigrationPreflight(
        Asset asset,
        Account account,
        InvestmentInstrument instrument,
        LegacyOpeningCostResolution cost,
        String sourceVersion,
        String requestHash,
        List<String> blockingErrors,
        List<String> warnings) {

    boolean isReady() {
        return blockingErrors.isEmpty() && cost != null && cost.isReady();
    }
}
