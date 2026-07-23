import { useEffect, useState } from 'react';
import api from '../api';
import { AssetAllocationChart } from '../components/charts/AssetAllocationChart';
import { MonthlyTrendChart } from '../components/charts/MonthlyTrendChart';
import { EmptyState } from '../components/Feedback';
import { MonthlyCashFlowPanel } from '../components/dashboard/MonthlyCashFlowPanel';
import { NetWorthHero } from '../components/dashboard/NetWorthHero';
import { RecentTransactionsPanel, type RecentTransaction } from '../components/dashboard/RecentTransactionsPanel';
import { getErrorMessage } from '../utils/error';

interface DashboardData {
    totalAssets: number;
    netWorth: number;
    monthIncome: number;
    monthExpense: number;
    monthNet: number;
    assetAllocation: { name: string; value: number; percentage: number }[];
    monthlyCashFlowTrend: { month: string; income: number; expense: number; net: number }[];
    recentTransactions: RecentTransaction[];
}

export default function Dashboard() {
    const [data, setData] = useState<DashboardData | null>(null);
    const [error, setError] = useState('');

    useEffect(() => {
        api.get('/dashboard').then(response => setData(response.data.data)).catch(reason => setError(getErrorMessage(reason, '财务概览加载失败')));
    }, []);

    if (error) return <EmptyState message={error} />;
    if (!data) return <EmptyState compact message="正在载入财务工作区…" />;

    return <div className="dashboard-workspace">
        <header className="dashboard-context"><div><p>财务总览</p><span>基于当前账户、资产与流水数据的账务汇总</span></div><span className="dashboard-context__currency">CNY</span></header>
        <section className="dashboard-overview" aria-label="财务摘要">
            <NetWorthHero totalAssets={data.totalAssets} netWorth={data.netWorth} />
            <MonthlyCashFlowPanel income={data.monthIncome} expense={data.monthExpense} net={data.monthNet} />
        </section>
        <section className="dashboard-analysis" aria-label="财务分析">
            <MonthlyTrendChart monthlyCashFlowTrend={data.monthlyCashFlowTrend} />
            <AssetAllocationChart assetAllocation={data.assetAllocation} />
        </section>
        <RecentTransactionsPanel transactions={data.recentTransactions} />
    </div>;
}
