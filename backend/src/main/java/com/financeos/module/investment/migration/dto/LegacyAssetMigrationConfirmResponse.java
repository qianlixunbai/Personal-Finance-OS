package com.financeos.module.investment.migration.dto;

public record LegacyAssetMigrationConfirmResponse(Long assetId, Long transactionId, Long accountId,
                                                  Long instrumentId, String requestHash, boolean idempotentReplay) {
}
