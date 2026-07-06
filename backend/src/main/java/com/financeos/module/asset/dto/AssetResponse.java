package com.financeos.module.asset.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AssetResponse(
        Long id,
        String name, String symbol, String type, String market, String currency,
        BigDecimal quantity, BigDecimal avgCost, BigDecimal currentPrice,
        BigDecimal marketValue, BigDecimal profitLoss, BigDecimal profitLossRate,
        LocalDateTime createdAt
) {}
