import type { ReferenceValuationFreshness, ReferenceValuationResponse, ReferenceValuationWarning } from '../types/reference-valuation';
import { isMarketQuoteSupported } from './marketQuote.ts';

type AssetWithReferenceValuation = { id: number; referenceValuation?: ReferenceValuationResponse | null };
type ReferenceValuationEligibleAsset = { type?: string | null; market?: string | null; symbol?: string | null };

const warningLabels: Record<string, string> = {
    QUOTE_STALE: '行情数据可能已过期',
    FX_STALE: '汇率数据可能已过期',
    QUOTE_MISSING: '暂无可用行情',
    FX_MISSING: '暂无可用汇率',
    REFRESH_FAILED: '刷新失败，已保留可用的旧数据',
    FEATURE_DISABLED: '汇率刷新功能当前未启用',
};

const freshnessLabels: Record<ReferenceValuationFreshness, string> = {
    FRESH: '数据新鲜',
    STALE: '使用旧快照',
    PARTIAL: '数据不完整',
    UNAVAILABLE: '暂无参考估值',
};

export function isReferenceValuationSupported(asset: ReferenceValuationEligibleAsset) {
    return isMarketQuoteSupported(asset);
}

export function formatReferenceValuationMoney(value: number | null | undefined, currency: string | null | undefined) {
    if (value === null || value === undefined || !Number.isFinite(value)) return '暂无';
    if (!currency) return value.toLocaleString('zh-CN');
    try {
        return new Intl.NumberFormat('zh-CN', { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value);
    } catch {
        return `${value.toLocaleString('zh-CN')} ${currency}`;
    }
}

export function referenceValuationFreshnessLabel(freshness: ReferenceValuationFreshness) {
    return freshnessLabels[freshness];
}

export function referenceValuationFxSummary(fxRequired: boolean, baseCurrency: string | null, quoteCurrency: string | null,
                                            rate: number | null) {
    if (!fxRequired) return '无需汇率换算';
    const pair = baseCurrency && quoteCurrency ? `${baseCurrency} → ${quoteCurrency}` : '所需汇率暂无';
    return rate === null ? `${pair}（汇率暂无）` : `${pair} · ${rate} ${quoteCurrency ?? ''}`.trim();
}

export function referenceValuationWarningMessages(warnings: readonly ReferenceValuationWarning[] | null | undefined) {
    return Array.from(new Set((warnings ?? []).map(warning => warningLabels[warning.code] ?? '参考数据存在提示，请稍后刷新')));
}

export function referenceValuationRefreshMessage(error: unknown) {
    const status = (error as { response?: { status?: number } }).response?.status;
    if (status === 400) return '当前资产暂不支持市场参考估值。';
    if (status === 404) return '资产不存在或无权访问。';
    if (status === 429) return '刷新请求过于频繁，请稍后再试。';
    if (status === 502 || status === 503) return '行情或汇率服务暂时不可用，已保留现有数据。';
    return '参考估值刷新失败，请稍后再试。';
}

export function updateAssetReferenceValuation<T extends AssetWithReferenceValuation>(assets: readonly T[], assetId: number,
                                                                                     referenceValuation: ReferenceValuationResponse): T[] {
    return assets.map(asset => asset.id === assetId ? { ...asset, referenceValuation } : asset);
}
