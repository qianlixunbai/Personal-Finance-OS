package com.financeos.module.investment.migration.dto;

import jakarta.validation.constraints.NotBlank;

public record LegacyAssetMigrationConfirmRequest(@NotBlank String previewToken) {
}
