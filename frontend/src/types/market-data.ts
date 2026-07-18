export type MarketQuoteFreshness = 'FRESH' | 'STALE';

export interface MarketQuote {
    symbol: string;
    market: string;
    currency: string;
    price: number;
    quoteTime: string;
    fetchedAt: string;
    provider: string;
    freshness: MarketQuoteFreshness;
}

export type MarketQuoteRefreshResult = 'CACHE_HIT' | 'UPDATED' | 'STALE_FALLBACK';

export interface MarketQuoteRefreshResponse extends MarketQuote {
    refreshResult: MarketQuoteRefreshResult;
    warning: string | null;
}
