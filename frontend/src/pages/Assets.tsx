import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { getErrorMessage } from '../utils/error';
import { formatCurrency } from '../utils/format';

interface Asset {
    id: number;
    name: string;
    symbol: string;
    type: string;
    market: string;
    currency: string;
    quantity: number;
    avgCost: number;
    currentPrice: number;
    marketValue: number;
    profitLoss: number;
    profitLossRate: number;
    createdAt: string;
}

interface PageResult<T> {
    records: T[];
    total: number;
    page: number;
    size: number;
}

const pageSize = 20;

export default function Assets() {
    const [assets, setAssets] = useState<Asset[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [selectedAsset, setSelectedAsset] = useState<Asset | null>(null);
    const [error, setError] = useState('');

    const fetchPage = async (targetPage = page) => {
        const res = await api.get('/assets/page', { params: { page: targetPage, size: pageSize } });
        const data: PageResult<Asset> = res.data.data;
        setAssets(data.records || []);
        setTotal(data.total || 0);
        setPage(data.page || targetPage);
    };

    useEffect(() => {
        fetchPage(1).catch(err => setError(getErrorMessage(err, '资产列表加载失败')));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const showDetail = async (id: number) => {
        setError('');
        try {
            const res = await api.get(`/assets/${id}`);
            setSelectedAsset(res.data.data);
        } catch (err) {
            setError(getErrorMessage(err, '资产详情加载失败'));
        }
    };

    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    const hasNextPage = page < totalPages && assets.length >= pageSize;

    return (
        <div>
            <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>投资资产</h2>
            </div>

            {error && <AlertMessage type="error">{error}</AlertMessage>}

            {selectedAsset && (
                <section style={{ background: '#fff', padding: 24, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'center', marginBottom: 16 }}>
                        <h3 style={{ margin: 0 }}>资产详情</h3>
                        <button type="button" onClick={() => setSelectedAsset(null)} style={{ padding: '6px 12px', background: '#636e72', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>关闭</button>
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}>
                        <DetailItem label="名称" value={selectedAsset.name} />
                        <DetailItem label="代码" value={selectedAsset.symbol || '-'} />
                        <DetailItem label="类型" value={selectedAsset.type} />
                        <DetailItem label="市场" value={selectedAsset.market || '-'} />
                        <DetailItem label="币种" value={selectedAsset.currency || 'CNY'} />
                        <DetailItem label="持仓数量" value={selectedAsset.quantity} />
                        <DetailItem label="平均成本" value={formatCurrency(selectedAsset.avgCost)} />
                        <DetailItem label="当前价格" value={selectedAsset.currentPrice ? formatCurrency(selectedAsset.currentPrice) : '-'} />
                        <DetailItem label="当前市值" value={formatCurrency(selectedAsset.marketValue)} />
                        <DetailItem label="浮动盈亏" value={`${formatCurrency(selectedAsset.profitLoss)} (${selectedAsset.profitLossRate?.toFixed(2)}%)`} />
                        <DetailItem label="创建时间" value={selectedAsset.createdAt?.replace('T', ' ') || '-'} />
                    </div>
                </section>
            )}

            <div className="table-scroll">
                <table style={{ width: '100%', borderCollapse: 'collapse', background: '#fff', borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <thead>
                        <tr>
                            <th style={thStyle}>名称</th>
                            <th style={thStyle}>持仓</th>
                            <th style={thStyle}>成本</th>
                            <th style={thStyle}>现价</th>
                            <th style={thStyle}>盈亏</th>
                            <th style={thStyle}>操作</th>
                        </tr>
                    </thead>
                    <tbody>
                        {assets.map(asset => (
                            <tr key={asset.id}>
                                <td style={tdStyle}>{asset.name} <span style={{ color: '#888' }}>{asset.symbol}</span></td>
                                <td style={tdStyle}>{asset.quantity}</td>
                                <td style={tdStyle}>{formatCurrency(asset.avgCost)}</td>
                                <td style={tdStyle}>{asset.currentPrice ? formatCurrency(asset.currentPrice) : '-'}</td>
                                <td style={{ ...tdStyle, color: asset.profitLoss >= 0 ? '#00b894' : '#e17055' }}>{formatCurrency(asset.profitLoss)} ({asset.profitLossRate?.toFixed(2)}%)</td>
                                <td style={tdStyle}><button type="button" onClick={() => showDetail(asset.id)} style={{ padding: '6px 12px', background: '#0984e3', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>详情</button></td>
                            </tr>
                        ))}
                        {assets.length === 0 && <EmptyTableRow colSpan={6} message="暂无资产" />}
                    </tbody>
                </table>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 16 }}>
                <span style={{ color: '#636e72' }}>第 {page} / {totalPages} 页，共 {total} 个资产</span>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={() => fetchPage(page - 1)} disabled={page <= 1} style={pageButtonStyle}>上一页</button>
                    <button onClick={() => fetchPage(page + 1)} disabled={!hasNextPage} style={pageButtonStyle}>下一页</button>
                </div>
            </div>
        </div>
    );
}

function DetailItem({ label, value }: { label: string; value: string | number }) {
    return (
        <div>
            <div style={{ color: '#888', fontSize: 13, marginBottom: 4 }}>{label}</div>
            <div style={{ fontWeight: 600 }}>{value}</div>
        </div>
    );
}

const thStyle: CSSProperties = {
    padding: '12px 16px',
    textAlign: 'left',
    borderBottom: '1px solid #eee',
};

const tdStyle: CSSProperties = {
    padding: '12px 16px',
    borderBottom: '1px solid #eee',
};

const pageButtonStyle: CSSProperties = {
    padding: '8px 14px',
    background: '#fff',
    border: '1px solid #ddd',
    borderRadius: 8,
    cursor: 'pointer',
};
