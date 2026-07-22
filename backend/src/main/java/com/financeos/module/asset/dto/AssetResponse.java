package com.financeos.module.asset.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;

public record AssetResponse(
        Long id,
        String name, String symbol, String type, String market, String currency,
        BigDecimal quantity, BigDecimal avgCost, BigDecimal currentPrice,
        BigDecimal marketValue, BigDecimal profitLoss, BigDecimal profitLossRate,
        LocalDateTime createdAt,
        MarketQuoteSnapshotResponse marketQuote,
        ReferenceValuationResponse referenceValuation
) {
    public AssetResponse(Long id, String name, String symbol, String type, String market, String currency,
                         BigDecimal quantity, BigDecimal avgCost, BigDecimal currentPrice, BigDecimal marketValue,
                         BigDecimal profitLoss, BigDecimal profitLossRate, LocalDateTime createdAt,
                         MarketQuoteSnapshotResponse marketQuote) {
        this(id, name, symbol, type, market, currency, quantity, avgCost, currentPrice, marketValue,
                profitLoss, profitLossRate, createdAt, marketQuote, null);
    }
}
