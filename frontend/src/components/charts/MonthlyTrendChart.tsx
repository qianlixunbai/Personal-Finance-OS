import { useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { Link } from 'react-router-dom';
import { formatCurrency } from '../../utils/format';
import { ChartCard } from './ChartCard';
import { EChart } from './EChart';

interface MonthlyCashFlow {
    month: string;
    income: number;
    expense: number;
    net: number;
}

function formatCompactCurrency(value: number) {
    const absoluteValue = Math.abs(value);
    if (absoluteValue >= 1_000_000) return `${value < 0 ? '-' : ''}¥${(absoluteValue / 1_000_000).toFixed(1).replace('.0', '')}m`;
    if (absoluteValue >= 1_000) return `${value < 0 ? '-' : ''}¥${(absoluteValue / 1_000).toFixed(1).replace('.0', '')}k`;
    return `${value < 0 ? '-' : ''}¥${absoluteValue}`;
}

export function MonthlyTrendChart({ monthlyCashFlowTrend }: { monthlyCashFlowTrend: MonthlyCashFlow[] }) {
    const hasCashFlow = monthlyCashFlowTrend.some(item => item.income !== 0 || item.expense !== 0);
    const option = useMemo<EChartsOption>(() => ({
        color: ['#10b981', '#f43f5e', '#22d3ee'],
        tooltip: {
            trigger: 'axis',
            backgroundColor: '#0e131b',
            borderColor: '#2a3746',
            borderWidth: 1,
            textStyle: { color: '#f7fafc', fontSize: 12 },
            extraCssText: 'box-shadow: 0 12px 28px rgba(0, 0, 0, .35);',
            axisPointer: { type: 'line', lineStyle: { color: 'rgba(34, 211, 238, .38)' } },
            formatter: (params) => {
                const items = Array.isArray(params) ? params : [params];
                const month = items[0]?.name ?? '';
                return `${month}<br/>${items.map(item => `${item.marker}${item.seriesName}：${formatCurrency(Number(item.value))}`).join('<br/>')}`;
            },
        },
        legend: { type: 'scroll', top: 4, right: 4, textStyle: { color: '#94a3b8', fontSize: 11 }, itemWidth: 10, itemHeight: 7 },
        grid: { top: 42, right: 12, bottom: 48, left: 56, containLabel: true },
        xAxis: {
            type: 'category',
            boundaryGap: false,
            data: monthlyCashFlowTrend.map(item => item.month),
            axisLabel: { rotate: 30, hideOverlap: true, color: '#94a3b8' },
            axisLine: { lineStyle: { color: '#2a3746' } },
            axisTick: { lineStyle: { color: '#2a3746' } },
        },
        yAxis: {
            type: 'value',
            axisLine: { lineStyle: { color: '#2a3746' } },
            axisTick: { lineStyle: { color: '#2a3746' } },
            axisLabel: { color: '#94a3b8', formatter: value => formatCompactCurrency(Number(value)) },
            splitLine: { lineStyle: { color: '#1d2733' } },
        },
        series: [
            { name: '收入', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 3 }, areaStyle: { color: 'rgba(16, 185, 129, .08)' }, emphasis: { focus: 'series' }, data: monthlyCashFlowTrend.map(item => item.income) },
            { name: '支出', type: 'line', smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2 }, emphasis: { focus: 'series' }, data: monthlyCashFlowTrend.map(item => item.expense) },
            { name: '结余', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, lineStyle: { type: 'dashed', width: 1.5, opacity: .78 }, emphasis: { focus: 'series' }, data: monthlyCashFlowTrend.map(item => item.net) },
        ],
    }), [monthlyCashFlowTrend]);

    return (
        <ChartCard title="最近 6 个月趋势" className="chart-card--trend" meta="收入 · 支出 · 结余" emptyMessage={hasCashFlow ? undefined : '记录交易后，这里将显示最近六个月的收支趋势。'} emptyAction={<Link to="/transactions" className="button button--secondary button--small">前往交易流水</Link>}>
            <EChart option={option} height={286} ariaLabel="最近 6 个月收入、支出和结余趋势图" />
        </ChartCard>
    );
}
