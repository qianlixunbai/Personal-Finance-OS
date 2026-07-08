import { useEffect, useState } from 'react';
import type { CSSProperties, FormEvent } from 'react';
import api from '../api';

interface Asset {
    id: number;
    name: string;
    symbol: string;
    type: string;
    market: string;
    quantity: number;
    avgCost: number;
    currentPrice: number;
    marketValue: number;
    profitLoss: number;
    profitLossRate: number;
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
    const [form, setForm] = useState({ name: '', symbol: '', type: 'STOCK', market: '', currency: 'CNY', quantity: 0, avgCost: 0 });

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
        fetch(1);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const create = async (e: FormEvent) => {
        e.preventDefault();
        await api.post('/assets', form);
        setShowForm(false);
        fetch(page);
    };

    const updatePrice = async (id: number) => {
        const price = prompt('输入当前价格:');
        if (price) {
            await api.put(`/assets/${id}/price?price=${price}`);
            fetch(page);
        }
    };

    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    const hasNextPage = page < totalPages && assets.length >= pageSize;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>投资资产</h2>
                <button onClick={() => setShowForm(!showForm)} style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>新增资产</button>
            </div>

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
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{a.avgCost}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{a.currentPrice || '-'}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee', color: a.profitLoss >= 0 ? '#00b894' : '#e17055' }}>{a.profitLoss?.toFixed(2)} ({a.profitLossRate?.toFixed(2)}%)</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>
                                <button onClick={() => updatePrice(a.id)} style={{ padding: '6px 12px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>更新价格</button>
                            </td>
                        </tr>
                    ))}
                    {assets.length === 0 && (
                        <tr><td colSpan={6} style={{ textAlign: 'center', color: '#999', padding: 40 }}>暂无资产</td></tr>
                    )}
                </tbody>
            </table>

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

const pageButtonStyle: CSSProperties = {
    padding: '8px 14px',
    background: '#fff',
    border: '1px solid #ddd',
    borderRadius: 8,
    cursor: 'pointer',
};
