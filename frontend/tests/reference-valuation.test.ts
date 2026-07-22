import assert from 'node:assert/strict';
import test from 'node:test';
import type { ReferenceValuationResponse } from '../src/types/reference-valuation.ts';
import {
    formatReferenceValuationMoney,
    referenceValuationFxSummary,
    referenceValuationFreshnessLabel,
    referenceValuationRefreshMessage,
    referenceValuationWarningMessages,
    updateAssetReferenceValuation,
} from '../src/utils/referenceValuation.ts';

const freshValuation: ReferenceValuationResponse = {
    assetId: 1, symbol: 'AAPL', quantity: 10, quoteCurrency: 'USD', quotePrice: 150.25,
    quoteTime: '2026-07-22T01:30:00Z', quoteFetchedAt: '2026-07-22T01:31:00Z', quoteProvider: 'TEST_QUOTE',
    quoteFreshness: 'FRESH', fxRequired: true, fxBaseCurrency: 'USD', fxQuoteCurrency: 'CNY', fxRate: 7.18,
    fxRateTime: '2026-07-22T01:30:00Z', fxFetchedAt: '2026-07-22T01:31:00Z', fxProvider: 'TEST_FX',
    fxFreshness: 'FRESH', nativeMarketValue: 1502.5, baseCurrency: 'CNY', baseCurrencyMarketValue: 10787.95,
    valuationFreshness: 'FRESH', calculatedAt: '2026-07-22T01:31:00Z', formulaVersion: 'v1', warnings: [],
};

test('formats backend valuation amounts with their supplied currency and never displays NaN', () => {
    assert.match(formatReferenceValuationMoney(freshValuation.quotePrice, freshValuation.quoteCurrency), /US\$/);
    assert.match(formatReferenceValuationMoney(freshValuation.nativeMarketValue, freshValuation.quoteCurrency), /US\$/);
    assert.match(formatReferenceValuationMoney(freshValuation.baseCurrencyMarketValue, freshValuation.baseCurrency), /¥/);
    assert.equal(formatReferenceValuationMoney(null, 'USD'), '暂无');
    assert.equal(formatReferenceValuationMoney(Number.NaN, 'USD'), '暂无');
});

test('maps every valuation freshness state and public warning code to safe Chinese copy', () => {
    assert.equal(referenceValuationFreshnessLabel('FRESH'), '数据新鲜');
    assert.equal(referenceValuationFreshnessLabel('STALE'), '使用旧快照');
    assert.equal(referenceValuationFreshnessLabel('PARTIAL'), '数据不完整');
    assert.equal(referenceValuationFreshnessLabel('UNAVAILABLE'), '暂无参考估值');
    assert.deepEqual(referenceValuationWarningMessages([
        { code: 'QUOTE_STALE', component: 'QUOTE', message: 'provider details' },
        { code: 'FX_STALE', component: 'FX', message: 'provider details' },
        { code: 'QUOTE_MISSING', component: 'QUOTE', message: 'provider details' },
        { code: 'FX_MISSING', component: 'FX', message: 'provider details' },
        { code: 'REFRESH_FAILED', component: 'FX', message: 'provider details' },
        { code: 'FEATURE_DISABLED', component: 'FX', message: 'provider details' },
        { code: 'UNKNOWN_CODE', component: 'FX', message: 'sensitive provider response' },
        { code: 'FX_STALE', component: 'FX', message: 'duplicate' },
    ]), [
        '行情数据可能已过期', '汇率数据可能已过期', '暂无可用行情', '暂无可用汇率',
        '刷新失败，已保留可用的旧数据', '汇率刷新功能当前未启用', '参考数据存在提示，请稍后刷新',
    ]);
});

test('updates only the refreshed asset reference valuation without changing manual fields', () => {
    const first = { id: 1, currentPrice: 10, marketValue: 100, avgCost: 8, profitLoss: 20, referenceValuation: null };
    const second = { id: 2, currentPrice: 20, marketValue: 200, avgCost: 18, profitLoss: 20, referenceValuation: null };
    const updated = updateAssetReferenceValuation([first, second], 2, freshValuation);

    assert.equal(updated[0], first);
    assert.equal(updated[1].currentPrice, 20);
    assert.equal(updated[1].marketValue, 200);
    assert.equal(updated[1].avgCost, 18);
    assert.equal(updated[1].profitLoss, 20);
    assert.deepEqual(updated[1].referenceValuation, freshValuation);
});

test('maps reference valuation refresh failures to fixed retryable messages', () => {
    assert.equal(referenceValuationRefreshMessage({ response: { status: 429 } }), '刷新请求过于频繁，请稍后再试。');
    assert.equal(referenceValuationRefreshMessage({ response: { status: 502 } }), '行情或汇率服务暂时不可用，已保留现有数据。');
    assert.equal(referenceValuationRefreshMessage({ response: { status: 503 } }), '行情或汇率服务暂时不可用，已保留现有数据。');
    assert.equal(referenceValuationRefreshMessage({ response: { status: 400 } }), '当前资产暂不支持市场参考估值。');
});

test('describes backend FX direction and CNY identity without calculating a reciprocal rate', () => {
    assert.equal(referenceValuationFxSummary(false, 'CNY', 'CNY', 1), '无需汇率换算');
    assert.equal(referenceValuationFxSummary(true, 'USD', 'CNY', null), 'USD → CNY（汇率暂无）');
    assert.equal(referenceValuationFxSummary(true, 'USD', 'CNY', 7.18), 'USD → CNY · 7.18 CNY');
});
