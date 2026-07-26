package com.financeos.module.investment.migration.dto;

import java.util.List;

public record LegacyAssetMigrationPreviewResponse(
        Source source, Target target, OpeningTransaction openingTransaction, ExpectedPosition expectedPosition,
        Validation validation, Confirmation confirmation) {
    public record Source(Long assetId, String name, String symbol, String market, String type, String currency,
                         String quantity, String avgCost, String totalCost, String totalCostSource,
                         String realizedProfitLoss, Long accountId, String positionMode, String positionStatus,
                         String sourceVersion) {
    }
    public record Target(Long instrumentId, String instrumentSymbol, String instrumentMarket, String instrumentAssetClass,
                         String instrumentStatus, Long accountId, String accountName, String accountType,
                         String accountCurrency, String accountStatus, boolean remapped) {
    }
    public record OpeningTransaction(String quantity, String unitPrice, String grossAmount, String fee, String tax,
                                     String netAmount, String realizedPnL, String currency, String cashDelta,
                                     String tradeTimePolicy) {
    }
    public record ExpectedPosition(String quantity, String avgCost, String totalCost, String realizedPnL,
                                   String status, Long accountId, Long instrumentId, Integer projectionVersion) {
    }
    public record Validation(List<String> blockingErrors, List<String> warnings, String roundingDifference,
                             String formulaVersion) {
    }
    public record Confirmation(String previewToken, String requestHash, String sourceVersion, String expiresAt,
                               String formulaVersion) {
    }
}
