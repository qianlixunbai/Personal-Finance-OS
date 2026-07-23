import { useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { formatCurrency } from '../../utils/format';
import { ChartCard } from './ChartCard';
import { EChart } from './EChart';

interface MonthlyCashFlowChartProps {
    monthIncome: number;
    monthExpense: number;
}

export function MonthlyCashFlowChart({ monthIncome, monthExpense }: MonthlyCashFlowChartProps) {
    const option = useMemo<EChartsOption>(() => ({
        tooltip: {
            trigger: 'axis',
            backgroundColor: '#0e131b',
            borderColor: '#2a3746',
            borderWidth: 1,
            textStyle: { color: '#f7fafc', fontSize: 12 },
            extraCssText: 'box-shadow: 0 12px 28px rgba(0, 0, 0, .35);',
            axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(34, 211, 238, .08)' } },
            formatter: (params) => {
                const items = Array.isArray(params) ? params : [params];
                return items.map(item => `${item.marker}${item.name}：${formatCurrency(Number(item.value))}`).join('<br/>');
            },
        },
        grid: { top: 20, right: 14, bottom: 44, left: 68, containLabel: true },
        xAxis: {
            type: 'category',
            data: ['本月收入', '本月支出'],
            axisTick: { alignWithLabel: true, lineStyle: { color: '#2a3746' } },
            axisLine: { lineStyle: { color: '#2a3746' } },
            axisLabel: { color: '#94a3b8' },
        },
        yAxis: {
            type: 'value',
            axisLine: { lineStyle: { color: '#2a3746' } },
            axisTick: { lineStyle: { color: '#2a3746' } },
            axisLabel: { color: '#94a3b8', formatter: value => formatCurrency(Number(value)) },
            splitLine: { lineStyle: { color: '#1d2733' } },
        },
        series: [{
            type: 'bar',
            barMaxWidth: 72,
            data: [
                { value: monthIncome, itemStyle: { color: '#10b981', borderRadius: [6, 6, 0, 0] } },
                { value: monthExpense, itemStyle: { color: '#f43f5e', borderRadius: [6, 6, 0, 0] } },
            ],
        }],
    }), [monthIncome, monthExpense]);

    return (
        <ChartCard title="本月收支对比" emptyMessage={monthIncome === 0 && monthExpense === 0 ? '本月暂无收支数据' : undefined}>
            <EChart option={option} ariaLabel="本月收入与支出柱状图" />
        </ChartCard>
    );
}
