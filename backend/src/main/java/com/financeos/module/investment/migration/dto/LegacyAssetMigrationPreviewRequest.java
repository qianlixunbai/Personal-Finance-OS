package com.financeos.module.investment.migration.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record LegacyAssetMigrationPreviewRequest(
        @NotNull @Positive Long instrumentId,
        @NotNull @Positive Long accountId) {
}
