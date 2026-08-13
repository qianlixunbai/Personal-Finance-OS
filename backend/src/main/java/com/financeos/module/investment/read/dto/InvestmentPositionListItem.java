package com.financeos.module.investment.read.dto;

public record InvestmentPositionListItem(Long positionId, String positionMode, Account account, Instrument instrument,
                                         String quantity, String averageCost, String totalCost,
                                         String cumulativeRealizedProfitLoss, String status,
                                         PositionReferenceValuation referenceValuation) {
    public InvestmentPositionListItem(Long positionId, String positionMode, Account account, Instrument instrument,
                                      String quantity, String averageCost, String totalCost,
                                      String cumulativeRealizedProfitLoss, String status) {
        this(positionId, positionMode, account, instrument, quantity, averageCost, totalCost,
                cumulativeRealizedProfitLoss, status, new PositionReferenceValuation("CNY", null, "UNAVAILABLE", java.util.List.of()));
    }
    public record Account(Long id, String displayName, String type, String status) {
        public Account(Long id, String displayName) {
            this(id, displayName, null, null);
        }
    }
    public record Instrument(Long id, String symbol, String name, String market, String assetClass, String quoteCurrency,
                             String status) {
        public Instrument(Long id, String symbol, String name, String market, String assetClass, String quoteCurrency) {
            this(id, symbol, name, market, assetClass, quoteCurrency, null);
        }
    }
    public record PositionReferenceValuation(String baseCurrency, String value, String freshness,
                                             java.util.List<Warning> warnings) {
        public PositionReferenceValuation {
            warnings = warnings == null ? java.util.List.of() : java.util.List.copyOf(warnings);
        }
    }
    public record Warning(String code, String component) { }
}
