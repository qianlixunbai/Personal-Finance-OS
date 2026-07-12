import { useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { formatCurrency } from '../../utils/format';
import { buildAssetAllocationData, type AssetAllocation, type AssetAllocationChartData } from './assetAllocationData';
import { ChartCard } from './ChartCard';
import { EChart } from './EChart';

export function AssetAllocationChart({ assetAllocation }: { assetAllocation: AssetAllocation[] }) {
    const entries = useMemo(() => assetAllocation.filter(item => item.value > 0), [assetAllocation]);
    const chartData = useMemo(() => buildAssetAllocationData(entries), [entries]);
    const option = useMemo<EChartsOption>(() => {
        return {
            tooltip: {
                trigger: 'item',
                formatter: (params) => {
                    const item = params as unknown as { marker?: string; data: AssetAllocationChartData };
                    return `${item.marker ?? ''}${item.data.name}<br/>市值：${formatCurrency(item.data.value)}<br/>占比：${item.data.percentage.toFixed(2)}%`;
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
                data: chartData,
            }],
        };
    }, [chartData]);

    return (
        <ChartCard title="投资资产分布" emptyMessage={entries.length === 0 ? '暂无有效投资资产分布' : undefined}>
            <EChart option={option} ariaLabel="投资资产分布环形图" />
        </ChartCard>
    );
}
