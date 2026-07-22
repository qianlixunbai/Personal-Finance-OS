import { useEffect, useState } from 'react';
import api from '../api';
import { AssetAllocationChart } from '../components/charts/AssetAllocationChart';
import { MonthlyCashFlowChart } from '../components/charts/MonthlyCashFlowChart';
import { MonthlyTrendChart } from '../components/charts/MonthlyTrendChart';
import { EmptyState } from '../components/Feedback';
import { Icon, SummaryCard } from '../components/Visual';
import { getErrorMessage } from '../utils/error';
import { formatCurrency, formatTransactionAmount, formatTransactionType } from '../utils/format';

interface DashboardData { totalAssets: number; netWorth: number; monthIncome: number; monthExpense: number; monthNet: number; assetAllocation: { name: string; value: number; percentage: number }[]; monthlyCashFlowTrend: { month: string; income: number; expense: number; net: number }[]; recentTransactions: { id: number; type: string; amount: number; category: string; account: string; date: string }[]; }

export default function Dashboard() {
    const [data, setData] = useState<DashboardData | null>(null);
    const [error, setError] = useState('');
    useEffect(() => { api.get('/dashboard').then(res => setData(res.data.data)).catch(err => setError(getErrorMessage(err, '财务概览加载失败'))); }, []);
    if (error) return <EmptyState message={error} />;
    if (!data) return <EmptyState message="加载中…" />;
    return <div>
        <header className="page-header"><div><h1 className="page-header__title">财务概览</h1><p className="page-header__subtitle">虚构演示数据的资产、收支与近期动态汇总</p></div></header>
        <section className="summary-grid" aria-label="财务摘要">
            <SummaryCard icon="assets" label="总资产">{formatCurrency(data.totalAssets)}</SummaryCard><SummaryCard icon="shield" label="净资产" tone="purple">{formatCurrency(data.netWorth)}</SummaryCard><SummaryCard icon="income" label="本月收入" tone="success">{formatCurrency(data.monthIncome)}</SummaryCard><SummaryCard icon="expense" label="本月支出" tone="danger">{formatCurrency(data.monthExpense)}</SummaryCard><SummaryCard icon="balance" label="本月结余" tone={data.monthNet >= 0 ? 'warning' : 'danger'}>{formatCurrency(data.monthNet)}</SummaryCard>
        </section>
        <section className="dashboard-analysis" aria-label="财务分析"><AssetAllocationChart assetAllocation={data.assetAllocation} /><MonthlyCashFlowChart monthIncome={data.monthIncome} monthExpense={data.monthExpense} /><MonthlyTrendChart monthlyCashFlowTrend={data.monthlyCashFlowTrend} /></section>
        <section className="content-card"><h2 className="content-card__title"><span className="content-card__title-icon"><Icon name="transactions" size={17} /></span>最近交易</h2><div className="table-scroll"><table className="data-table"><thead><tr><th>日期</th><th>账户</th><th>分类</th><th>类型</th><th className="cell-number">金额</th></tr></thead><tbody>{data.recentTransactions.map(tx => <tr key={tx.id}><td>{tx.date}</td><td>{tx.account}</td><td>{tx.category}</td><td>{formatTransactionType(tx.type)}</td><td className="cell-number"><span className={`amount amount--${tx.type === 'INCOME' ? 'positive' : tx.type === 'EXPENSE' ? 'negative' : 'neutral'}`}>{formatTransactionAmount(tx.type, tx.amount)}</span></td></tr>)}</tbody></table></div></section>
    </div>;
}
