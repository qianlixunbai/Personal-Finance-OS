import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../api';
import { AssetAllocationChart } from '../components/charts/AssetAllocationChart';
import { MonthlyCashFlowChart } from '../components/charts/MonthlyCashFlowChart';
import { MonthlyTrendChart } from '../components/charts/MonthlyTrendChart';
import { EmptyState } from '../components/Feedback';
import { getErrorMessage } from '../utils/error';
import { formatCurrency, formatTransactionAmount, formatTransactionType, transactionAmountColor } from '../utils/format';

interface DashboardData {
    totalAssets: number;
    netWorth: number;
    monthIncome: number;
    monthExpense: number;
    monthNet: number;
    assetAllocation: { name: string; value: number; percentage: number }[];
    monthlyCashFlowTrend: { month: string; income: number; expense: number; net: number }[];
    recentTransactions: { id: number; type: string; amount: number; category: string; account: string; date: string }[];
}

export default function Dashboard() {
    const [data, setData] = useState<DashboardData | null>(null);
    const [error, setError] = useState('');

    useEffect(() => {
        api.get('/dashboard')
            .then(res => setData(res.data.data))
            .catch(err => setError(getErrorMessage(err, '财务概览加载失败')));
    }, []);

    if (error) return <EmptyState message={error} />;
    if (!data) return <EmptyState message="加载中..." />;

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

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 320px), 1fr))', gap: 20, marginBottom: 20 }}>
                <div style={{ minWidth: 0 }}>
                    <AssetAllocationChart assetAllocation={data.assetAllocation} />
                </div>
                <div style={{ minWidth: 0 }}>
                    <MonthlyCashFlowChart monthIncome={data.monthIncome} monthExpense={data.monthExpense} />
                </div>
            </div>

            <div style={{ minWidth: 0, marginBottom: 20 }}>
                <MonthlyTrendChart monthlyCashFlowTrend={data.monthlyCashFlowTrend} />
            </div>

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
                                <td colSpan={5}>
                                    <EmptyState message="暂无最近流水" />
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </section>
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
