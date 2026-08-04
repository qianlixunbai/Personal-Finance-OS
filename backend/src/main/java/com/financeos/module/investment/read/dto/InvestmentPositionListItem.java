package com.financeos.module.investment.read.dto;

public record InvestmentPositionListItem(Long positionId, String positionMode, Account account, Instrument instrument,
                                         String quantity, String averageCost, String totalCost,
                                         String cumulativeRealizedProfitLoss, String status) {
    public record Account(Long id, String displayName) { }
    public record Instrument(Long id, String symbol, String name, String market, String assetClass, String quoteCurrency) { }
}
