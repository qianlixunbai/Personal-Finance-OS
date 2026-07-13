import { useEffect, useState } from 'react';
import type { CSSProperties, FormEvent } from 'react';
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
    const [showForm, setShowForm] = useState(false);
    const [selectedAsset, setSelectedAsset] = useState<Asset | null>(null);
    const [form, setForm] = useState({ name: '', symbol: '', type: 'STOCK', market: '', currency: 'CNY', quantity: 0, avgCost: 0 });
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    const fetch = async (targetPage = page) => {
        const res = await api.get('/assets/page', { params: { page: targetPage, size: pageSize } });
        const data: PageResult<Asset> = res.data.data;
        const records = data.records || [];

        if (records.length === 0 && targetPage > 1 && (data.total || 0) > 0) {
            await fetch(targetPage - 1);
            return;
        }

        setAssets(records);
        setTotal(data.total || 0);
        setPage(data.page || targetPage);
    };

    useEffect(() => {
        fetch(1).catch(err => setError(getErrorMessage(err, '资产列表加载失败')));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const create = async (e: FormEvent) => {
        e.preventDefault();
        setError('');
        setSuccess('');
        try {
            await api.post('/assets', form);
            setShowForm(false);
            setSuccess('资产已创建');
            await fetch(page);
        } catch (err) {
            setError(getErrorMessage(err, '资产保存失败'));
        }
    };

    const showDetail = async (id: number) => {
        setError('');
        setSuccess('');
        try {
            const res = await api.get(`/assets/${id}`);
            setSelectedAsset(res.data.data);
        } catch (err) {
            setError(getErrorMessage(err, '资产详情加载失败'));
        }
    };

    const updatePrice = async (id: number) => {
        const price = prompt('输入当前价格:');
        if (price) {
            setError('');
            setSuccess('');
            try {
                await api.put(`/assets/${id}/price?price=${price}`);
                setSuccess('资产价格已更新');
                await fetch(page);
                if (selectedAsset?.id === id) {
                    await showDetail(id);
                }
            } catch (err) {
                setError(getErrorMessage(err, '资产价格更新失败'));
            }
        }
    };

    const closeAsset = async (asset: Asset) => {
        if (!confirm('确认将该资产标记为已清仓？此操作不会计算卖出收益。')) return;

        setError('');
        setSuccess('');
        try {
            const res = await api.put(`/assets/${asset.id}/close`);
            setSuccess('资产已清仓');
            await fetch(page);
            if (selectedAsset?.id === asset.id) {
                setSelectedAsset(res.data.data);
            }
        } catch (err) {
            setError(getErrorMessage(err, '资产清仓失败'));
        }
    };

    const remove = async (asset: Asset) => {
        if (!confirm(`确定删除资产「${asset.name}」吗？`)) return;

        setError('');
        setSuccess('');
        try {
            await api.delete(`/assets/${asset.id}`);
            const nextPage = assets.length === 1 && page > 1 ? page - 1 : page;
            if (selectedAsset?.id === asset.id) {
                setSelectedAsset(null);
            }
            setSuccess('资产已删除');
            await fetch(nextPage);
        } catch (err) {
            setError(getErrorMessage(err, '资产删除失败'));
        }
    };

    const hasPosition = (asset: Asset) => Number(asset.quantity) > 0;
    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    const hasNextPage = page < totalPages && assets.length >= pageSize;

    return (
        <div>
            <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>投资资产</h2>
                <button onClick={() => setShowForm(!showForm)} style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>新增资产</button>
            </div>

            {error && <AlertMessage type="error">{error}</AlertMessage>}
            {success && <AlertMessage type="success">{success}</AlertMessage>}

            {showForm && (
                <form onSubmit={create} style={{ background: '#fff', padding: 24, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                        <div>
                            <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>名称</label>
                            <input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8 }} />
                        </div>
                        <div>
                            <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>代码</label>
                            <input value={form.symbol} onChange={e => setForm({ ...form, symbol: e.target.value })} style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8 }} />
                        </div>
                        <div>
                            <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>数量</label>
                            <input type="number" step="0.01" value={form.quantity} onChange={e => setForm({ ...form, quantity: +e.target.value })} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8 }} />
                        </div>
                        <div>
                            <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>平均成本</label>
                            <input type="number" step="0.01" value={form.avgCost} onChange={e => setForm({ ...form, avgCost: +e.target.value })} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8 }} />
                        </div>
                    </div>
                    <button type="submit" style={{ marginTop: 16, padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>保存</button>
                </form>
            )}

            {selectedAsset && (
                <section style={{ background: '#fff', padding: 24, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'center', marginBottom: 16 }}>
                        <h3 style={{ margin: 0 }}>资产详情</h3>
                        <div style={{ display: 'flex', gap: 8 }}>
                            {hasPosition(selectedAsset) && <button onClick={() => closeAsset(selectedAsset)} style={{ padding: '6px 12px', background: '#00b894', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>清仓</button>}
                            <button onClick={() => setSelectedAsset(null)} style={{ padding: '6px 12px', background: '#636e72', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>关闭</button>
                        </div>
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
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>名称</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>持仓</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>成本</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>现价</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>盈亏</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>操作</th>
                    </tr>
                </thead>
                <tbody>
                    {assets.map(a => (
                        <tr key={a.id}>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{a.name} <span style={{ color: '#888' }}>{a.symbol}</span></td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{a.quantity}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{formatCurrency(a.avgCost)}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{a.currentPrice ? formatCurrency(a.currentPrice) : '-'}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee', color: a.profitLoss >= 0 ? '#00b894' : '#e17055' }}>{formatCurrency(a.profitLoss)} ({a.profitLossRate?.toFixed(2)}%)</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>
                                <div style={{ display: 'flex', gap: 8 }}>
                                    <button onClick={() => showDetail(a.id)} style={{ padding: '6px 12px', background: '#0984e3', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>详情</button>
                                    <button onClick={() => updatePrice(a.id)} style={{ padding: '6px 12px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>更新价格</button>
                                    {hasPosition(a) && <button onClick={() => closeAsset(a)} style={{ padding: '6px 12px', background: '#00b894', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>清仓</button>}
                                    <button onClick={() => remove(a)} style={{ padding: '6px 12px', background: '#e17055', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>删除</button>
                                </div>
                            </td>
                        </tr>
                    ))}
                    {assets.length === 0 && (
                        <EmptyTableRow colSpan={6} message="暂无资产" />
                    )}
                </tbody>
            </table>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 16 }}>
                <span style={{ color: '#636e72' }}>第 {page} / {totalPages} 页，共 {total} 个资产</span>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={() => fetch(page - 1)} disabled={page <= 1} style={pageButtonStyle}>上一页</button>
                    <button onClick={() => fetch(page + 1)} disabled={!hasNextPage} style={pageButtonStyle}>下一页</button>
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

const pageButtonStyle: CSSProperties = {
    padding: '8px 14px',
    background: '#fff',
    border: '1px solid #ddd',
    borderRadius: 8,
    cursor: 'pointer',
};
