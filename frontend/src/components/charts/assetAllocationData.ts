export interface AssetAllocation {
    name: string;
    value: number;
    percentage: number;
}

export interface AssetAllocationChartData extends AssetAllocation {
    originalName: string;
}

export function buildAssetAllocationData(entries: AssetAllocation[]): AssetAllocationChartData[] {
    const nameCounts = new Map<string, number>();
    entries.forEach(item => nameCounts.set(item.name, (nameCounts.get(item.name) ?? 0) + 1));

    const seenCounts = new Map<string, number>();
    return entries.map(item => {
        const occurrence = (seenCounts.get(item.name) ?? 0) + 1;
        seenCounts.set(item.name, occurrence);
        return {
            name: nameCounts.get(item.name)! > 1 ? `${item.name}（${occurrence}）` : item.name,
            originalName: item.name,
            value: item.value,
            percentage: item.percentage,
        };
    });
}
