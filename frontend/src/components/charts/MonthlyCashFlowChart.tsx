import { useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { formatCurrency } from '../../utils/format';
import { ChartCard } from './ChartCard';
import { EChart } from './EChart';
import { useWideViewport } from './useWideViewport';

interface MonthlyCashFlowChartProps {
    monthIncome: number;
    monthExpense: number;
}

export function MonthlyCashFlowChart({ monthIncome, monthExpense }: MonthlyCashFlowChartProps) {
    const isWideViewport = useWideViewport();
    const option = useMemo<EChartsOption>(() => ({
        tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            ...(isWideViewport ? { textStyle: { fontSize: 13 } } : {}),
            formatter: (params) => {
                const items = Array.isArray(params) ? params : [params];
                return items.map(item => `${item.marker}${item.name}：${formatCurrency(Number(item.value))}`).join('<br/>');
            },
        },
        grid: { top: 20, right: 14, bottom: 44, left: 68 },
        xAxis: {
            type: 'category',
            data: ['本月收入', '本月支出'],
            axisTick: { alignWithLabel: true },
            axisLine: { lineStyle: { color: '#cbd5e1' } },
            axisLabel: { color: '#64748b', ...(isWideViewport ? { fontSize: 13 } : {}) },
        },
        yAxis: {
            type: 'value',
            axisLabel: { color: '#64748b', formatter: value => formatCurrency(Number(value)), ...(isWideViewport ? { fontSize: 13 } : {}) },
            splitLine: { lineStyle: { color: '#e8eef7' } },
        },
        series: [{
            type: 'bar',
            barMaxWidth: 72,
            data: [
                { value: monthIncome, itemStyle: { color: '#059669', borderRadius: [5, 5, 0, 0] } },
                { value: monthExpense, itemStyle: { color: '#e5482d', borderRadius: [5, 5, 0, 0] } },
            ],
        }],
    }), [isWideViewport, monthIncome, monthExpense]);

    return (
        <ChartCard title="本月收支对比" emptyMessage={monthIncome === 0 && monthExpense === 0 ? '本月暂无收支数据' : undefined}>
            <EChart option={option} ariaLabel="本月收入与支出柱状图" />
        </ChartCard>
    );
}
