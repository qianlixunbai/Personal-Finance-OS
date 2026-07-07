import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../api';

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
                <Card label="本月收入" value={data.monthIncome} color="#00b894" />
                <Card label="本月支出" value={data.monthExpense} color="#e17055" />
                <Card label="本月结余" value={data.monthNet} color={data.monthNet >= 0 ? '#00b894' : '#e17055'} />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px,1fr))', gap: 12, marginBottom: 28 }}>
                <Link to="/accounts" style={{ padding: 16, background: '#f8f9fa', borderRadius: 10, textDecoration: 'none', color: '#2d3436', fontWeight: 600 }}>账户管理</Link>
                <Link to="/assets" style={{ padding: 16, background: '#f8f9fa', borderRadius: 10, textDecoration: 'none', color: '#2d3436', fontWeight: 600 }}>投资资产</Link>
                <Link to="/transactions" style={{ padding: 16, background: '#f8f9fa', borderRadius: 10, textDecoration: 'none', color: '#2d3436', fontWeight: 600 }}>交易流水</Link>
            </div>

            <div style={{ background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                <h3>最近交易</h3>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
                    <thead>
                        <tr>
                            <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>日期</th>
                            <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>金额</th>
                            <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>类型</th>
                        </tr>
                    </thead>
                    <tbody>
                        {data.recentTransactions.map(tx => (
                            <tr key={tx.id}>
                                <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{tx.date}</td>
                                <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee', color: tx.type === 'INCOME' ? '#00b894' : '#e17055', fontWeight: 600 }}>{tx.amount}</td>
                                <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{tx.type}</td>
                            </tr>
                        ))}
                        {data.recentTransactions.length === 0 && (
                            <tr><td colSpan={3} style={{ textAlign: 'center', color: '#999', padding: 40 }}>暂无数据</td></tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}

function Card({ label, value, color }: { label: string; value: number; color: string }) {
    return (
        <div style={{ background: '#fff', borderRadius: 16, padding: '28px 24px', boxShadow: '0 2px 20px rgba(0,0,0,.06)', textAlign: 'center' }}>
            <div style={{ fontSize: 13, color: '#888', marginBottom: 8 }}>{label}</div>
            <div style={{ fontSize: 28, fontWeight: 700, color }}>¥{value?.toFixed(2)}</div>
        </div>
    );
}
