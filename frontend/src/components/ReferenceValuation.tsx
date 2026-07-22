import { Badge } from './Visual';
import type { ReferenceValuationResponse } from '../types/reference-valuation';
import { formatQuoteTime } from '../utils/marketQuote';
import {
    formatReferenceValuationMoney,
    isReferenceValuationSupported,
    referenceValuationFxSummary,
    referenceValuationFreshnessLabel,
    referenceValuationWarningMessages,
} from '../utils/referenceValuation';

type ReferenceValuationAsset = {
    type: string;
    market: string;
    symbol: string;
    referenceValuation?: ReferenceValuationResponse | null;
};

export function ReferenceValuationCell({ asset }: { asset: ReferenceValuationAsset }) {
    if (!isReferenceValuationSupported(asset)) return <span className="table-secondary">暂不支持</span>;
    const valuation = asset.referenceValuation;
    if (!valuation) return <span className="table-secondary">暂无参考估值</span>;
    return <div className="reference-valuation-cell">
        <div className="reference-valuation-cell__value">{formatReferenceValuationMoney(valuation.baseCurrencyMarketValue, valuation.baseCurrency)}</div>
        <span className="table-secondary">状态：{referenceValuationFreshnessLabel(valuation.valuationFreshness)}</span>
    </div>;
}

export function ReferenceValuationDetails({ asset }: { asset: ReferenceValuationAsset }) {
    if (!isReferenceValuationSupported(asset)) {
        return <section className="reference-valuation"><h3 className="content-card__title">市场参考估值</h3><p className="table-secondary">当前资产暂不支持市场参考估值</p></section>;
    }
    const valuation = asset.referenceValuation;
    if (!valuation) {
        return <section className="reference-valuation"><h3 className="content-card__title">市场参考估值</h3><p className="table-secondary">暂无参考估值，可使用“刷新参考估值”获取最新参考数据。</p></section>;
    }
    const warnings = referenceValuationWarningMessages(valuation.warnings);
    const statusTone = valuation.valuationFreshness === 'FRESH' ? 'success' : valuation.valuationFreshness === 'UNAVAILABLE' ? 'danger' : 'warning';
    const fxPair = valuation.fxBaseCurrency && valuation.fxQuoteCurrency ? `${valuation.fxBaseCurrency} → ${valuation.fxQuoteCurrency}` : '暂无';
    const fxRate = referenceValuationFxSummary(valuation.fxRequired, valuation.fxBaseCurrency, valuation.fxQuoteCurrency, valuation.fxRate);

    return <section className="reference-valuation">
        <div className="reference-valuation__heading"><h3 className="content-card__title">市场参考估值</h3><Badge tone={statusTone} dot>{referenceValuationFreshnessLabel(valuation.valuationFreshness)}</Badge></div>
        <p className="table-secondary">仅供参考，不修改人工估值</p>
        <div className="detail-grid">
            <DetailItem label="参考行情" value={formatReferenceValuationMoney(valuation.quotePrice, valuation.quoteCurrency)} />
            <DetailItem label="行情状态" value={valuation.quoteFreshness === 'FRESH' ? '数据新鲜' : valuation.quoteFreshness === 'STALE' ? '使用旧快照' : '暂无'} />
            <DetailItem label="行情时间" value={formatQuoteTime(valuation.quoteTime)} />
            <DetailItem label="行情获取时间" value={formatQuoteTime(valuation.quoteFetchedAt)} />
            <DetailItem label="行情来源" value={valuation.quoteProvider || '暂无'} />
            <DetailItem label="原生参考市值" value={formatReferenceValuationMoney(valuation.nativeMarketValue, valuation.quoteCurrency)} />
            {valuation.fxRequired ? <>
                <DetailItem label="参考汇率" value={fxPair} />
                <DetailItem label="汇率" value={fxRate} />
                <DetailItem label="汇率状态" value={valuation.fxFreshness === 'FRESH' ? '数据新鲜' : valuation.fxFreshness === 'STALE' ? '使用旧快照' : '暂无'} />
                <DetailItem label="汇率时间" value={formatQuoteTime(valuation.fxRateTime)} />
                <DetailItem label="汇率获取时间" value={formatQuoteTime(valuation.fxFetchedAt)} />
                <DetailItem label="汇率来源" value={valuation.fxProvider || '暂无'} />
            </> : <DetailItem label="汇率换算" value="无需汇率换算" />}
            <DetailItem label={`市场参考估值（${valuation.baseCurrency}）`} value={formatReferenceValuationMoney(valuation.baseCurrencyMarketValue, valuation.baseCurrency)} />
        </div>
        {warnings.length > 0 && <ul className="reference-valuation__warnings">{warnings.map(message => <li key={message}>{message}</li>)}</ul>}
    </section>;
}

function DetailItem({ label, value }: { label: string; value: string | number }) {
    return <div className="detail-item"><div className="detail-item__label">{label}</div><div className="detail-item__value">{value}</div></div>;
}
