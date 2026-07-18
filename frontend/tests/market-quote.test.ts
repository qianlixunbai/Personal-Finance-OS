import assert from 'node:assert/strict';
import test from 'node:test';
import type { MarketQuote, MarketQuoteRefreshResponse } from '../src/types/market-data.ts';
import {
    extractMarketQuote,
    formatQuoteTime,
    formatReferenceQuote,
    isMarketQuoteSupported,
    marketQuoteRefreshMessage,
    marketQuoteRefreshWarning,
    quoteFreshnessLabel,
    updateAssetMarketQuote,
} from '../src/utils/marketQuote.ts';

const quote: MarketQuote = {
    symbol: 'AAPL', market: 'US', currency: 'USD', price: 212.34,
    quoteTime: '2026-07-18T00:00:00Z', fetchedAt: '2026-07-18T00:01:00Z', provider: 'TEST', freshness: 'FRESH',
};

test('formats fresh, stale, missing, and invalid-time quote states safely', () => {
    assert.match(formatReferenceQuote(quote), /212\.34/);
    assert.match(formatReferenceQuote(quote), /US\$/);
    assert.equal(quoteFreshnessLabel(quote), '最新');
    assert.equal(quoteFreshnessLabel({ ...quote, freshness: 'STALE' }), '已过期');
    assert.equal(formatReferenceQuote(null), '尚未获取参考行情');
    assert.equal(formatQuoteTime('not-a-date'), '-');
    assert.notEqual(formatQuoteTime(quote.quoteTime), '-');
});

test('maps refresh errors to safe user messages', () => {
    assert.equal(marketQuoteRefreshMessage({ response: { status: 404 } }), '资产不存在、无权访问，或该证券无法获取行情。');
    assert.equal(marketQuoteRefreshMessage({ response: { status: 429 } }), '行情刷新请求过于频繁，请稍后再试。');
    assert.equal(marketQuoteRefreshMessage({ response: { status: 502 } }), '行情服务暂时不可用，请稍后重试。');
    assert.equal(marketQuoteRefreshMessage({ response: { status: 503 } }), '行情服务暂时不可用，请稍后重试。');
});

test('replaces stale fallback warning with a fixed Chinese message', () => {
    const staleFallback: MarketQuoteRefreshResponse = {
        ...quote, freshness: 'STALE', refreshResult: 'STALE_FALLBACK', warning: 'provider failure details',
    };
    assert.equal(marketQuoteRefreshWarning(staleFallback), '行情刷新失败，当前展示最近一次成功获取的参考行情。');
    assert.equal(marketQuoteRefreshWarning({ ...staleFallback, refreshResult: 'UPDATED', warning: 'ignored' }), null);
});

test('allows quote refresh only for US STOCK or ETF assets with a nonblank symbol', () => {
    assert.equal(isMarketQuoteSupported({ type: 'STOCK', market: 'US', symbol: 'AAPL' }), true);
    assert.equal(isMarketQuoteSupported({ type: 'ETF', market: 'US', symbol: 'QQQ' }), true);
    assert.equal(isMarketQuoteSupported({ type: 'stock', market: 'us', symbol: 'AAPL' }), true);
    assert.equal(isMarketQuoteSupported({ type: 'ETF', market: ' Us ', symbol: 'QQQ' }), true);

    assert.equal(isMarketQuoteSupported({ type: 'STOCK', market: 'CN', symbol: '600519' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'ETF', market: 'HK', symbol: '0700' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'STOCK', market: '', symbol: 'AAPL' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'STOCK', market: null, symbol: 'AAPL' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'STOCK', market: 'US', symbol: '' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'STOCK', market: 'US', symbol: '   ' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'STOCK', market: 'US', symbol: null }), false);
    assert.equal(isMarketQuoteSupported({ type: 'CASH', market: 'US', symbol: 'CASH' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'FUND', market: 'US', symbol: 'FUND' }), false);
    assert.equal(isMarketQuoteSupported({ type: 'CRYPTO', market: 'US', symbol: 'BTC' }), false);
});

test('keeps only base quote fields and updates one asset without touching valuation', () => {
    const refresh: MarketQuoteRefreshResponse = { ...quote, freshness: 'STALE', refreshResult: 'STALE_FALLBACK', warning: 'cached quote shown' };
    const extracted = extractMarketQuote(refresh);
    assert.deepEqual(extracted, { ...quote, freshness: 'STALE' });
    assert.equal('refreshResult' in extracted, false);
    const first = { id: 1, currentPrice: 10, marketValue: 100, marketQuote: null };
    const second = { id: 2, currentPrice: 20, marketValue: 200, marketQuote: null };
    const updated = updateAssetMarketQuote([first, second], 2, extracted);
    assert.equal(updated[0], first);
    assert.equal(updated[1].currentPrice, 20);
    assert.equal(updated[1].marketValue, 200);
    assert.deepEqual(updated[1].marketQuote, extracted);
});
