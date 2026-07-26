package com.financeos.module.investment.migration;

import com.financeos.module.asset.entity.Asset;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class LegacyMigrationHashes {
    private LegacyMigrationHashes() {
    }

    static String sourceVersion(Asset asset) {
        return sha256(String.join("|",
                "assetId=" + value(asset.getId()),
                "userId=" + value(asset.getUserId()),
                "quantity=" + decimal(asset.getQuantity(), 8),
                "avgCost=" + decimal(asset.getAvgCost(), 8),
                "totalCost=" + decimal(asset.getTotalCost(), 2),
                "realizedProfitLoss=" + decimal(asset.getRealizedProfitLoss(), 2),
                "currency=" + value(asset.getCurrency()),
                "type=" + value(asset.getType()),
                "accountId=" + value(asset.getAccountId()),
                "instrumentId=" + value(asset.getInstrumentId()),
                "positionMode=" + value(asset.getPositionMode()),
                "positionStatus=" + value(asset.getPositionStatus()),
                "projectionVersion=" + value(asset.getProjectionVersion())));
    }

    static String requestHash(long userId, Asset asset, long accountId, long instrumentId,
                              String sourceVersion, LegacyOpeningCostResolution cost) {
        return sha256(String.join("|",
                "operation=LEGACY_OPENING_MIGRATION",
                "formulaVersion=LEGACY_OPENING_MIGRATION_V1",
                "userId=" + userId,
                "assetId=" + asset.getId(),
                "accountId=" + accountId,
                "instrumentId=" + instrumentId,
                "sourceVersion=" + sourceVersion,
                "quantity=" + decimal(asset.getQuantity(), 8),
                "totalCost=" + decimal(cost.totalCost(), 2),
                "unitPrice=" + decimal(cost.unitPrice(), 8),
                "totalCostSource=" + value(cost.totalCostSource())));
    }

    private static String decimal(BigDecimal value, int scale) {
        if (value == null) {
            return "<null>";
        }
        try {
            return value.setScale(scale).toPlainString();
        } catch (ArithmeticException exception) {
            return "invalid-scale:" + value.toPlainString();
        }
    }

    private static String value(Object value) {
        return value == null ? "<null>" : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
