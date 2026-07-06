package com.financeos.module.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record AssetRequest(
        @NotBlank String name,
        String symbol,
        @NotBlank String type,
        String market,
        String currency,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal avgCost
) {}
