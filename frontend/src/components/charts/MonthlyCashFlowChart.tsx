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
            axisPointer: { type: 'shadow' },
            formatter: (params) => {
                const items = Array.isArray(params) ? params : [params];
                return items.map(item => `${item.marker}${item.name}：${formatCurrency(Number(item.value))}`).join('<br/>');
            },
        },
        grid: { top: 24, right: 16, bottom: 48, left: 72 },
        xAxis: {
            type: 'category',
            data: ['本月收入', '本月支出'],
            axisTick: { alignWithLabel: true },
        },
        yAxis: {
            type: 'value',
            axisLabel: { formatter: value => formatCurrency(Number(value)) },
        },
        series: [{
            type: 'bar',
            barMaxWidth: 72,
            data: [
                { value: monthIncome, itemStyle: { color: '#00b894' } },
                { value: monthExpense, itemStyle: { color: '#e17055' } },
            ],
        }],
    }), [monthIncome, monthExpense]);

    return (
        <ChartCard title="本月收支对比" emptyMessage={monthIncome === 0 && monthExpense === 0 ? '本月暂无收支数据' : undefined}>
            <EChart option={option} ariaLabel="本月收入与支出柱状图" />
        </ChartCard>
    );
}
