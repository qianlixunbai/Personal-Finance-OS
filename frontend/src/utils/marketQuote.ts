import type { MarketQuote, MarketQuoteRefreshResponse } from '../types/market-data';

type AssetWithMarketQuote = { id: number; marketQuote?: MarketQuote | null };

export function formatReferenceQuote(quote: MarketQuote | null | undefined) {
    if (!quote) return '尚未获取参考行情';
    try {
        return new Intl.NumberFormat('zh-CN', {
            style: 'currency',
            currency: quote.currency,
            minimumFractionDigits: 2,
            maximumFractionDigits: 4,
        }).format(quote.price);
    } catch {
        return `${Number(quote.price).toFixed(2)} ${quote.currency || ''}`.trim();
    }
}

export function quoteFreshnessLabel(quote: MarketQuote | null | undefined) {
    if (!quote) return '尚未获取';
    return quote.freshness === 'FRESH' ? '最新' : '已过期';
}

export function formatQuoteTime(value: string | null | undefined) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '-';
    return new Intl.DateTimeFormat('zh-CN', {
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    }).format(date);
}

export function extractMarketQuote(response: MarketQuoteRefreshResponse): MarketQuote {
    const { refreshResult: _refreshResult, warning: _warning, ...quote } = response;
    return quote;
}

export function marketQuoteRefreshMessage(error: unknown) {
    const status = (error as { response?: { status?: number } }).response?.status;
    if (status === 404) return '资产不存在、无权访问，或该证券无法获取行情。';
    if (status === 429) return '行情刷新请求过于频繁，请稍后再试。';
    if (status === 502 || status === 503) return '行情服务暂时不可用，请稍后重试。';
    return '行情刷新失败，请稍后再试。';
}

export function marketQuoteRefreshWarning(response: MarketQuoteRefreshResponse) {
    return response.refreshResult === 'STALE_FALLBACK'
        ? '行情刷新失败，当前展示最近一次成功获取的参考行情。'
        : null;
}

export function updateAssetMarketQuote<T extends AssetWithMarketQuote>(assets: readonly T[], assetId: number,
                                                                        marketQuote: MarketQuote): T[] {
    return assets.map(asset => asset.id === assetId ? { ...asset, marketQuote } : asset);
}
