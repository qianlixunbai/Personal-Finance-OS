export type InvestmentPositionStatus = 'OPEN' | 'CLOSED' | 'ALL';
export type InvestmentCorrectionStatus = 'UNCHANGED' | 'REVERSED' | 'REPLACED';
export type InvestmentTransactionType = 'OPENING_POSITION' | 'BUY' | 'SELL' | 'DIVIDEND';
export type InvestmentFreshness = 'FRESH' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE';
export type InvestmentReceiptApplicability = 'POSTING_TIME' | 'CORRECTION_FINAL' | 'NOT_APPLICABLE';

export interface InvestmentWarning { code: string; component: string; }
export interface InvestmentAccount { id: number; displayName: string; }
export interface InvestmentInstrument { id: number; symbol: string; name: string; market: string; assetClass: string; quoteCurrency: string; }
export interface InvestmentReferenceValuation { baseCurrency: string; value: string | null; freshness: InvestmentFreshness; warnings: InvestmentWarning[]; }

export interface InvestmentPortfolio {
    currency: string;
    positionCount: number;
    openPositionCount: number;
    closedPositionCount: number;
    openTotalCost: string | null;
    cumulativeRealizedProfitLoss: string | null;
    referenceValuation: InvestmentReferenceValuation & { valuedPositionCount: number; totalOpenPositionCount: number; };
}

export interface InvestmentPositionListItem {
    positionId: number;
    positionMode: string;
    account: InvestmentAccount;
    instrument: InvestmentInstrument;
    quantity: string | null;
    averageCost: string | null;
    totalCost: string | null;
    cumulativeRealizedProfitLoss: string | null;
    status: Exclude<InvestmentPositionStatus, 'ALL'>;
    referenceValuation: InvestmentReferenceValuation;
}

export interface InvestmentPositionDetail extends Omit<InvestmentPositionListItem, 'referenceValuation'> {
    manualReference: { currentPrice: string | null; marketValue: string | null; } | null;
    referenceValuation: {
        accountingTruth: boolean;
        quotePrice: string | null;
        quoteCurrency: string | null;
        quoteTime: string | null;
        quoteFetchedAt: string | null;
        quoteProvider: string | null;
        fxRate: string | null;
        fxBaseCurrency: string | null;
        fxQuoteCurrency: string | null;
        fxRateTime: string | null;
        fxFetchedAt: string | null;
        fxProvider: string | null;
        baseCurrency: string;
        baseCurrencyValue: string | null;
        freshness: InvestmentFreshness;
        warnings: InvestmentWarning[];
    };
}

export interface CursorPage<T> { records: T[]; nextCursor: string | null; hasMore: boolean; size: number; }

export interface InvestmentTransactionListItem {
    logicalTransactionId: number;
    positionId: number;
    account: InvestmentAccount;
    instrument: InvestmentInstrument;
    transactionType: InvestmentTransactionType;
    effectiveTradeTime: string;
    quantity: string | null;
    unitPrice: string | null;
    grossAmount: string | null;
    feeAmount: string | null;
    taxAmount: string | null;
    netAmount: string | null;
    releasedCostAmount: string | null;
    realizedProfitLoss: string | null;
    correctionStatus: InvestmentCorrectionStatus;
    effective: boolean;
    correctionCreatedAt: string | null;
}

export interface InvestmentBusinessValues {
    quantity: string | null; unitPrice: string | null; grossAmount: string | null; feeAmount: string | null;
    taxAmount: string | null; netAmount: string | null; releasedCostAmount: string | null;
    realizedProfitLoss: string | null; note: string | null; externalReference: string | null;
}
export interface InvestmentReceipt {
    accountBalanceAfter: string | null; positionQuantityAfter: string | null; positionAverageCostAfter: string | null;
    positionTotalCostAfter: string | null; positionRealizedProfitLossAfter: string | null; positionStatusAfter: string | null;
}
export interface InvestmentCurrentPosition {
    quantity: string | null; averageCost: string | null; totalCost: string | null;
    cumulativeRealizedProfitLoss: string | null; status: string | null;
}
export interface InvestmentTransactionDetail {
    logicalTransactionId: number; transactionType: InvestmentTransactionType; correctionStatus: InvestmentCorrectionStatus;
    effective: boolean; effectiveTradeTime: string; settlementTime: string | null;
    account: InvestmentAccount; instrument: InvestmentInstrument;
    originalBusinessValues: InvestmentBusinessValues;
    effectiveBusinessValues: InvestmentBusinessValues | null;
    postingReceipt: InvestmentReceipt | null;
    correctionFinalReceipt: InvestmentReceipt | null;
    correction: { createdAt: string; reason: string; reversalTransactionId: number | null; replacementTransactionId: number | null; } | null;
    currentPosition: InvestmentCurrentPosition;
}

export interface InvestmentAuditEvent {
    eventKind: string; createdAt: string; physicalFactId: number | null; originalFactId: number | null; factType: string | null;
    reason: string | null; receiptApplicability: InvestmentReceiptApplicability;
    businessValues: InvestmentBusinessValues | null;
    postingReceipt: InvestmentReceipt | null;
    correctionFinalReceipt: InvestmentReceipt | null;
    facts: Array<{ role: string; physicalFactId: number; factType: string; receiptApplicability: InvestmentReceiptApplicability; businessValues: InvestmentBusinessValues; }>;
}
export interface InvestmentAuditTimeline {
    logicalTransactionId: number;
    correctionStatus: InvestmentCorrectionStatus;
    events: InvestmentAuditEvent[];
    currentPosition: InvestmentCurrentPosition;
}
