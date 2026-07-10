import { useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { formatCurrency } from '../../utils/format';
import { ChartCard } from './ChartCard';
import { EChart } from './EChart';

interface AssetAllocation {
    name: string;
    value: number;
    percentage: number;
}

export function AssetAllocationChart({ assetAllocation }: { assetAllocation: AssetAllocation[] }) {
    const entries = useMemo(() => assetAllocation.filter(item => item.value > 0), [assetAllocation]);
    const option = useMemo<EChartsOption>(() => {
        const percentageByName = new Map(entries.map(item => [item.name, item.percentage]));
        return {
            tooltip: {
                trigger: 'item',
                formatter: (params) => {
                    const item = params as { marker?: string; name?: string; value?: number };
                    const percentage = percentageByName.get(item.name ?? '') ?? 0;
                    return `${item.marker ?? ''}${item.name ?? ''}<br/>市值：${formatCurrency(item.value)}<br/>占比：${percentage.toFixed(2)}%`;
                },
            },
            legend: { type: 'scroll', bottom: 0, left: 'center' },
            series: [{
                type: 'pie',
                radius: ['45%', '70%'],
                center: ['50%', '42%'],
                avoidLabelOverlap: true,
                label: { show: false },
                labelLine: { show: false },
                emphasis: { label: { show: true, fontWeight: 'bold' } },
                data: entries.map(item => ({ name: item.name, value: item.value })),
            }],
        };
    }, [entries]);

    return (
        <ChartCard title="投资资产分布" emptyMessage={entries.length === 0 ? '暂无有效投资资产分布' : undefined}>
            <EChart option={option} ariaLabel="投资资产分布环形图" />
        </ChartCard>
    );
}
