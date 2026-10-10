import { format } from 'echarts/core';
import { formatCurrency } from '../../utils/format.ts';
import type { AssetAllocationChartData } from './assetAllocationData.ts';

export function formatAssetAllocationTooltip(item: { marker?: string; data: AssetAllocationChartData }) {
    // Only the ECharts-generated marker and our layout are trusted HTML.
    // Asset names must remain literal text in the HTML tooltip context.
    return `${item.marker ?? ''}${format.encodeHTML(item.data.name)}<br/>市值：${formatCurrency(item.data.value)}<br/>占比：${item.data.percentage.toFixed(2)}%`;
}
