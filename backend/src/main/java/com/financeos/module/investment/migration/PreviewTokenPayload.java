package com.financeos.module.investment.migration;

record PreviewTokenPayload(long userId, long assetId, long instrumentId, long accountId,
                           String sourceVersion, String requestHash, String formulaVersion, long expiresAtEpochSecond) {
}
