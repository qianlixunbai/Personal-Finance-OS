package com.financeos.module.investment.migration;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.investment.migration.dto.LegacyAssetMigrationPreviewResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class LegacyAssetMigrationPreviewService {
    private final LegacyAssetMigrationPreflightService preflightService;
    private final MigrationPreviewTokenService tokenService;

    public LegacyAssetMigrationPreviewService(LegacyAssetMigrationPreflightService preflightService,
                                              MigrationPreviewTokenService tokenService) {
        this.preflightService = preflightService;
        this.tokenService = tokenService;
    }

    public LegacyAssetMigrationPreviewResponse preview(Long userId, Long assetId, Long instrumentId, Long accountId) {
        LegacyAssetMigrationPreflight preflight = preflightService.preflight(userId, assetId, instrumentId, accountId);
        Asset asset = preflight.asset();
        LegacyOpeningCostResolution cost = preflight.cost();
        MigrationPreviewTokenService.CreatedPreviewToken token = preflight.isReady()
                ? tokenService.create(userId, assetId, instrumentId, accountId, preflight.sourceVersion(), preflight.requestHash())
                : null;
        return new LegacyAssetMigrationPreviewResponse(
                new LegacyAssetMigrationPreviewResponse.Source(asset.getId(), asset.getName(), asset.getSymbol(),
                        asset.getMarket(), asset.getType(), asset.getCurrency(), decimal(asset.getQuantity()),
                        decimal(asset.getAvgCost()), decimal(asset.getTotalCost()), cost.totalCostSource(),
                        decimal(asset.getRealizedProfitLoss()), asset.getAccountId(), asset.getPositionMode(),
                        asset.getPositionStatus(), preflight.sourceVersion()),
                new LegacyAssetMigrationPreviewResponse.Target(preflight.instrument().getId(), preflight.instrument().getSymbol(),
                        preflight.instrument().getMarket(), preflight.instrument().getAssetClass().name(),
                        preflight.instrument().getStatus().name(), preflight.account().getId(), preflight.account().getName(),
                        preflight.account().getType(), preflight.account().getCurrency(), preflight.account().getStatus(),
                        !java.util.Objects.equals(asset.getAccountId(), accountId)),
                new LegacyAssetMigrationPreviewResponse.OpeningTransaction(decimal(asset.getQuantity()), decimal(cost.unitPrice()),
                        decimal(cost.totalCost()), "0.00", "0.00", "0.00", "0.00", "CNY", "0.00",
                        "CONFIRMATION_INSTANT"),
                new LegacyAssetMigrationPreviewResponse.ExpectedPosition(decimal(asset.getQuantity()), decimal(cost.expectedAvgCost()),
                        decimal(cost.totalCost()), "0.00", "OPEN", accountId, instrumentId,
                        asset.getProjectionVersion() == null ? null : asset.getProjectionVersion() + 1),
                new LegacyAssetMigrationPreviewResponse.Validation(preflight.blockingErrors(), preflight.warnings(),
                        roundingDifference(asset, cost), MigrationPreviewTokenService.FORMULA_VERSION),
                token == null ? null : new LegacyAssetMigrationPreviewResponse.Confirmation(token.value(), preflight.requestHash(),
                        preflight.sourceVersion(), Instant.ofEpochSecond(token.expiresAtEpochSecond()).toString(),
                        MigrationPreviewTokenService.FORMULA_VERSION));
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private String roundingDifference(Asset asset, LegacyOpeningCostResolution cost) {
        if (asset.getAvgCost() == null || asset.getQuantity() == null || cost.totalCost() == null) {
            return null;
        }
        return asset.getQuantity().multiply(asset.getAvgCost()).setScale(2, java.math.RoundingMode.HALF_UP)
                .subtract(cost.totalCost()).abs().toPlainString();
    }
}
