export type ReferenceValuationFreshness = 'FRESH' | 'STALE' | 'PARTIAL' | 'UNAVAILABLE';
export type ReferenceInputFreshness = 'FRESH' | 'STALE' | 'NEVER_FETCHED' | null;

export interface ReferenceValuationWarning {
    code: string;
    component: string;
    message: string;
}

export interface ReferenceValuationResponse {
    assetId: number;
    symbol: string | null;
    quantity: number;
    quoteCurrency: string | null;
    quotePrice: number | null;
    quoteTime: string | null;
    quoteFetchedAt: string | null;
    quoteProvider: string | null;
    quoteFreshness: ReferenceInputFreshness;
    fxRequired: boolean;
    fxBaseCurrency: string | null;
    fxQuoteCurrency: string | null;
    fxRate: number | null;
    fxRateTime: string | null;
    fxFetchedAt: string | null;
    fxProvider: string | null;
    fxFreshness: ReferenceInputFreshness;
    nativeMarketValue: number | null;
    baseCurrency: string;
    baseCurrencyMarketValue: number | null;
    valuationFreshness: ReferenceValuationFreshness;
    calculatedAt: string;
    formulaVersion: string;
    warnings: ReferenceValuationWarning[];
}
