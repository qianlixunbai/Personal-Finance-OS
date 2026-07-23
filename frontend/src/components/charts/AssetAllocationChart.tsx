import { useMemo } from 'react';
import type { EChartsOption } from 'echarts';
import { Link } from 'react-router-dom';
import { formatCurrency } from '../../utils/format';
import { buildAssetAllocationData, type AssetAllocation, type AssetAllocationChartData } from './assetAllocationData';
import { ChartCard } from './ChartCard';
import { EChart } from './EChart';

const allocationColors = ['#22d3ee', '#3b82f6', '#8b5cf6', '#10b981', '#64748b'];

export function AssetAllocationChart({ assetAllocation }: { assetAllocation: AssetAllocation[] }) {
    const entries = useMemo(() => assetAllocation.filter(item => item.value > 0), [assetAllocation]);
    const chartData = useMemo(() => buildAssetAllocationData(entries), [entries]);
    const { visibleEntries, remainingEntries, chartEntries } = useMemo(() => {
        const rankedEntries = [...chartData].sort((left, right) => right.value - left.value);
        const visibleEntries = rankedEntries.slice(0, 4);
        const remainingEntries = rankedEntries.slice(4);
        const chartEntries = remainingEntries.length === 0 ? visibleEntries : [...visibleEntries, {
            name: '其他', originalName: '其他', value: remainingEntries.reduce((total, item) => total + item.value, 0), percentage: remainingEntries.reduce((total, item) => total + item.percentage, 0),
        }];
        return { visibleEntries, remainingEntries, chartEntries };
    }, [chartData]);
    const option = useMemo<EChartsOption>(() => {
        return {
            tooltip: {
                trigger: 'item',
                backgroundColor: '#0e131b',
                borderColor: '#2a3746',
                borderWidth: 1,
                textStyle: { color: '#f7fafc', fontSize: 12 },
                extraCssText: 'box-shadow: 0 12px 28px rgba(0, 0, 0, .35);',
                formatter: (params) => {
                    const item = params as unknown as { marker?: string; data: AssetAllocationChartData };
                    return `${item.marker ?? ''}${item.data.name}<br/>市值：${formatCurrency(item.data.value)}<br/>占比：${item.data.percentage.toFixed(2)}%`;
                },
            },
            color: allocationColors,
            legend: { show: false },
            series: [{
                type: 'pie',
                radius: ['48%', '72%'],
                center: ['50%', '42%'],
                avoidLabelOverlap: true,
                label: { show: false },
                labelLine: { show: false },
                emphasis: { label: { show: true, fontWeight: 'bold', color: '#f7fafc' }, itemStyle: { shadowBlur: 14, shadowColor: 'rgba(34, 211, 238, .22)' } },
                data: chartEntries,
            }],
        };
    }, [chartEntries]);

    return (
        <ChartCard title="投资资产分布" className="chart-card--allocation" meta="按人工市值" emptyMessage={entries.length === 0 ? '添加资产后，这里将显示投资配置。' : undefined} emptyAction={<Link to="/assets" className="button button--secondary button--small">前往资产</Link>}>
            <div className="allocation-layout">
                <EChart option={option} height={206} ariaLabel="投资资产分布环形图" />
                <ol className="allocation-ranking" aria-label="投资资产类别排名">
                    {visibleEntries.map((item, index) => <li key={item.name}><span className="allocation-ranking__name"><i style={{ backgroundColor: allocationColors[index] }} aria-hidden="true" />{item.name}</span><strong>{formatCurrency(item.value)}</strong><span>{item.percentage.toFixed(1)}%</span></li>)}
                    {remainingEntries.length > 0 && <li><span className="allocation-ranking__name"><i style={{ backgroundColor: allocationColors[4] }} aria-hidden="true" />其他 {remainingEntries.length} 类</span><strong>{formatCurrency(remainingEntries.reduce((total, item) => total + item.value, 0))}</strong><span>{remainingEntries.reduce((total, item) => total + item.percentage, 0).toFixed(1)}%</span></li>}
                </ol>
            </div>
        </ChartCard>
    );
}
