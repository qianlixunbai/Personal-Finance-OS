import { useEffect, useRef } from 'react';
import * as echarts from 'echarts/core';
import type { EChartsOption, EChartsType } from 'echarts';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

interface EChartProps {
    option: EChartsOption;
    height?: number;
    ariaLabel: string;
}

export function EChart({ option, height = 300, ariaLabel }: EChartProps) {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const chartRef = useRef<EChartsType | null>(null);

    useEffect(() => {
        const container = containerRef.current;
        if (!container) return;

        const chart = echarts.getInstanceByDom(container) ?? echarts.init(container);
        chartRef.current = chart;
        const resize = () => {
            if (!chart.isDisposed()) {
                chart.resize();
            }
        };
        const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(resize) : null;
        observer?.observe(container);
        if (!observer) {
            window.addEventListener('resize', resize);
        }

        return () => {
            observer?.disconnect();
            window.removeEventListener('resize', resize);
            if (!chart.isDisposed()) {
                chart.dispose();
            }
            if (chartRef.current === chart) {
                chartRef.current = null;
            }
        };
    }, []);

    useEffect(() => {
        const chart = chartRef.current;
        if (chart && !chart.isDisposed()) {
            chart.setOption(option, { notMerge: true, lazyUpdate: true });
        }
    }, [option]);

    return <div ref={containerRef} role="img" aria-label={ariaLabel} style={{ height, width: '100%' }} />;
}
