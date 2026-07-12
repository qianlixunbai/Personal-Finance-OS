import { useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { formatCurrency } from '../../utils/format';
import { ChartCard } from './ChartCard';
import { EChart } from './EChart';

interface MonthlyCashFlow {
    month: string;
    income: number;
    expense: number;
    net: number;
}

export function MonthlyTrendChart({ monthlyCashFlowTrend }: { monthlyCashFlowTrend: MonthlyCashFlow[] }) {
    const hasCashFlow = monthlyCashFlowTrend.some(item => item.income !== 0 || item.expense !== 0);
    const option = useMemo<EChartsOption>(() => ({
        color: ['#00b894', '#e17055', '#0984e3'],
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'line' },
            formatter: (params) => {
                const items = Array.isArray(params) ? params : [params];
                const month = items[0]?.name ?? '';
                return `${month}<br/>${items.map(item => `${item.marker}${item.seriesName}：${formatCurrency(Number(item.value))}`).join('<br/>')}`;
            },
        },
        legend: { type: 'scroll', top: 0, left: 'center' },
        grid: { top: 52, right: 16, bottom: 64, left: 72, containLabel: true },
        xAxis: {
            type: 'category',
            boundaryGap: false,
            data: monthlyCashFlowTrend.map(item => item.month),
            axisLabel: { rotate: 30, hideOverlap: true },
        },
        yAxis: {
            type: 'value',
            axisLabel: { formatter: value => formatCurrency(Number(value)) },
        },
        series: [
            { name: '收入', type: 'line', data: monthlyCashFlowTrend.map(item => item.income) },
            { name: '支出', type: 'line', data: monthlyCashFlowTrend.map(item => item.expense) },
            { name: '结余', type: 'line', lineStyle: { type: 'dashed' }, data: monthlyCashFlowTrend.map(item => item.net) },
        ],
    }), [monthlyCashFlowTrend]);

    return (
        <ChartCard title="最近 6 个月收支趋势" emptyMessage={hasCashFlow ? undefined : '最近 6 个月暂无收支数据'}>
            <EChart option={option} height={320} ariaLabel="最近 6 个月收入、支出和结余趋势图" />
        </ChartCard>
    );
}
