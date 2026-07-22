import { useEffect, useState } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { PageHeader } from '../components/PageHeader';
import { Pagination } from '../components/Pagination';
import { Badge, Icon, SummaryCard } from '../components/Visual';
import { getErrorMessage } from '../utils/error';
import { formatCurrency } from '../utils/format';

interface Asset { id: number; name: string; symbol: string; type: string; market: string; currency: string; quantity: number; avgCost: number; currentPrice: number; marketValue: number; profitLoss: number; profitLossRate: number; createdAt: string; }
interface PageResult<T> { records: T[]; total: number; page: number; size: number; }
const pageSize = 20;

export default function Assets() {
    const [assets, setAssets] = useState<Asset[]>([]); const [total, setTotal] = useState(0); const [page, setPage] = useState(1); const [selectedAsset, setSelectedAsset] = useState<Asset | null>(null); const [error, setError] = useState('');
    const fetchPage = async (targetPage = page) => { const res = await api.get('/assets/page', { params: { page: targetPage, size: pageSize } }); const data: PageResult<Asset> = res.data.data; setAssets(data.records || []); setTotal(data.total || 0); setPage(data.page || targetPage); };
    useEffect(() => { fetchPage(1).catch(err => setError(getErrorMessage(err, '资产列表加载失败'))); }, []);
    const totalMarketValue = assets.reduce((sum, asset) => sum + asset.marketValue, 0); const totalProfitLoss = assets.reduce((sum, asset) => sum + asset.profitLoss, 0); const totalPages = Math.max(1, Math.ceil(total / pageSize));
    return <div>
        <PageHeader title="投资资产" subtitle="展示本地固定的虚构持仓和人工估值；不连接外部行情服务" />
        <section className="summary-grid summary-grid--three" aria-label="资产概览"><SummaryCard icon="assets" label="持仓资产数">{total}</SummaryCard><SummaryCard icon="wallet" label="当前页市值" tone="purple">{formatCurrency(totalMarketValue)}</SummaryCard><SummaryCard icon="balance" label="当前页浮动盈亏" tone={totalProfitLoss >= 0 ? 'success' : 'danger'}>{formatCurrency(totalProfitLoss)}</SummaryCard></section>
        {error && <AlertMessage type="error">{error}</AlertMessage>}
        {selectedAsset && <section className="content-card"><div className="page-header"><h2 className="content-card__title">资产详情</h2><button type="button" className="pagination__button" onClick={() => setSelectedAsset(null)}>关闭</button></div><div className="detail-grid"><Detail label="名称" value={selectedAsset.name} /><Detail label="代码" value={selectedAsset.symbol || '-'} /><Detail label="类型" value={selectedAsset.type} /><Detail label="市场" value={selectedAsset.market || '-'} /><Detail label="币种" value={selectedAsset.currency || 'CNY'} /><Detail label="持仓数量" value={selectedAsset.quantity} /><Detail label="平均成本" value={formatCurrency(selectedAsset.avgCost)} /><Detail label="固定演示价格" value={formatCurrency(selectedAsset.currentPrice)} /><Detail label="当前市值" value={formatCurrency(selectedAsset.marketValue)} /><Detail label="浮动盈亏" value={`${formatCurrency(selectedAsset.profitLoss)} (${selectedAsset.profitLossRate.toFixed(2)}%)`} /></div></section>}
        <section className="content-card"><h2 className="content-card__title"><span className="content-card__title-icon"><Icon name="assets" size={17} /></span>持仓资产明细</h2><div className="table-scroll"><table className="data-table"><thead><tr><th>名称 / 代码</th><th>类型</th><th className="cell-number">持仓</th><th className="cell-number">成本</th><th className="cell-number">固定演示价格</th><th className="cell-number">市值</th><th className="cell-number">盈亏</th><th>查看</th></tr></thead><tbody>{assets.map(asset => <tr key={asset.id}><td><span className="table-primary">{asset.name}</span><span className="table-secondary">{asset.symbol || '—'}</span></td><td><Badge tone={asset.type === 'STOCK' ? 'primary' : 'warning'}>{asset.type}</Badge></td><td className="cell-number">{asset.quantity}</td><td className="cell-number">{formatCurrency(asset.avgCost)}</td><td className="cell-number">{formatCurrency(asset.currentPrice)}</td><td className="cell-number">{formatCurrency(asset.marketValue)}</td><td className="cell-number"><span className={`amount amount--${asset.profitLoss >= 0 ? 'positive' : 'negative'}`}>{formatCurrency(asset.profitLoss)} ({asset.profitLossRate.toFixed(2)}%)</span></td><td><button type="button" className="pagination__button" onClick={() => setSelectedAsset(asset)}>详情</button></td></tr>)}{assets.length === 0 && <EmptyTableRow colSpan={8} message="暂无资产" />}</tbody></table></div><Pagination page={page} totalPages={totalPages} summary={<>共 {total} 个资产</>} previousDisabled={page <= 1} nextDisabled={page >= totalPages} onPrevious={() => fetchPage(page - 1)} onNext={() => fetchPage(page + 1)} /></section>
    </div>;
}

function Detail({ label, value }: { label: string; value: string | number }) { return <div className="detail-item"><div className="detail-item__label">{label}</div><div className="detail-item__value">{value}</div></div>; }
