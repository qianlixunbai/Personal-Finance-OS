import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../api';
import { formatCurrency, formatTransactionAmount, formatTransactionType, transactionAmountColor } from '../utils/format';

interface DashboardData {
    totalAssets: number;
    netWorth: number;
    monthIncome: number;
    monthExpense: number;
    monthNet: number;
    assetAllocation: { name: string; value: number; percentage: number }[];
    recentTransactions: { id: number; type: string; amount: number; category: string; account: string; date: string }[];
}

export default function Dashboard() {
    const [data, setData] = useState<DashboardData | null>(null);

    useEffect(() => {
        api.get('/dashboard').then(res => setData(res.data.data));
    }, []);

    if (!data) return <div>加载中...</div>;

    return (
        <div>
            <h2>财务概览</h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px,1fr))', gap: 20, marginBottom: 28 }}>
                <Card label="总资产" value={data.totalAssets} color="#00b894" />
                <Card label="净资产" value={data.netWorth} color="#0984e3" />
                <Card label="本月收入" value={data.monthIncome} color="#00b894" />
                <Card label="本月支出" value={data.monthExpense} color="#e17055" />
                <Card label="本月结余" value={data.monthNet} color={data.monthNet >= 0 ? '#00b894' : '#e17055'} />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px,1fr))', gap: 12, marginBottom: 28 }}>
                <Link to="/accounts" style={{ padding: 16, background: '#f8f9fa', borderRadius: 10, textDecoration: 'none', color: '#2d3436', fontWeight: 600 }}>账户管理</Link>
                <Link to="/assets" style={{ padding: 16, background: '#f8f9fa', borderRadius: 10, textDecoration: 'none', color: '#2d3436', fontWeight: 600 }}>投资资产</Link>
                <Link to="/transactions" style={{ padding: 16, background: '#f8f9fa', borderRadius: 10, textDecoration: 'none', color: '#2d3436', fontWeight: 600 }}>交易流水</Link>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px,1fr))', gap: 20 }}>
                <section style={{ background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <h3 style={{ marginTop: 0 }}>最近交易</h3>
                    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
                        <thead>
                            <tr>
                                <th style={thStyle}>日期</th>
                                <th style={thStyle}>账户</th>
                                <th style={thStyle}>分类</th>
                                <th style={thStyle}>类型</th>
                                <th style={{ ...thStyle, textAlign: 'right' }}>金额</th>
                            </tr>
                        </thead>
                        <tbody>
                            {data.recentTransactions.map(tx => (
                                <tr key={tx.id}>
                                    <td style={tdStyle}>{tx.date}</td>
                                    <td style={tdStyle}>{tx.account}</td>
                                    <td style={tdStyle}>{tx.category}</td>
                                    <td style={tdStyle}>{formatTransactionType(tx.type)}</td>
                                    <td style={{ ...tdStyle, textAlign: 'right', color: transactionAmountColor(tx.type), fontWeight: 600 }}>
                                        {formatTransactionAmount(tx.type, tx.amount)}
                                    </td>
                                </tr>
                            ))}
                            {data.recentTransactions.length === 0 && (
                                <tr>
                                    <td colSpan={5} style={{ textAlign: 'center', color: '#999', padding: 40 }}>暂无最近流水</td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </section>

                <section style={{ background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <h3 style={{ marginTop: 0 }}>资产分布</h3>
                    {data.assetAllocation.length > 0 ? (
                        <div style={{ display: 'grid', gap: 12 }}>
                            {data.assetAllocation.map(item => (
                                <div key={item.name}>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginBottom: 6 }}>
                                        <span style={{ fontWeight: 600 }}>{item.name}</span>
                                        <span style={{ color: '#636e72' }}>{formatCurrency(item.value)} / {item.percentage.toFixed(2)}%</span>
                                    </div>
                                    <div style={{ height: 8, background: '#f1f2f6', borderRadius: 4, overflow: 'hidden' }}>
                                        <div style={{ width: `${Math.min(item.percentage, 100)}%`, height: '100%', background: '#0984e3' }} />
                                    </div>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div style={{ textAlign: 'center', color: '#999', padding: 40 }}>暂无资产分布</div>
                    )}
                </section>
            </div>
        </div>
    );
}

function Card({ label, value, color }: { label: string; value: number; color: string }) {
    return (
        <div style={{ background: '#fff', borderRadius: 16, padding: '28px 24px', boxShadow: '0 2px 20px rgba(0,0,0,.06)', textAlign: 'center' }}>
            <div style={{ fontSize: 13, color: '#888', marginBottom: 8 }}>{label}</div>
            <div style={{ fontSize: 28, fontWeight: 700, color }}>{formatCurrency(value)}</div>
        </div>
    );
}

const thStyle = {
    padding: '12px 16px',
    textAlign: 'left' as const,
    borderBottom: '1px solid #eee',
};

const tdStyle = {
    padding: '12px 16px',
    borderBottom: '1px solid #eee',
};
