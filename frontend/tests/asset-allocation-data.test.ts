import assert from 'node:assert/strict';
import test from 'node:test';

type AssetAllocation = {
    name: string;
    value: number;
    percentage: number;
};

async function buildItems(entries: AssetAllocation[]) {
    const module = await import('../src/components/charts/assetAllocationData.ts')
        .catch(() => ({ buildAssetAllocationData: () => [] }));
    return module.buildAssetAllocationData(entries);
}

test('keeps each same-name asset as a distinct tooltip and legend item', async () => {
    const items = await buildItems([
        { name: '腾讯', value: 60, percentage: 60 },
        { name: '腾讯', value: 25, percentage: 25 },
        { name: '腾讯', value: 15, percentage: 15 },
    ]);

    assert.deepEqual(items, [
        { name: '腾讯（1）', originalName: '腾讯', value: 60, percentage: 60 },
        { name: '腾讯（2）', originalName: '腾讯', value: 25, percentage: 25 },
        { name: '腾讯（3）', originalName: '腾讯', value: 15, percentage: 15 },
    ]);
});

test('keeps unique names unchanged and preserves backend values', async () => {
    const items = await buildItems([
        { name: '沪深 300 ETF', value: 60, percentage: 60 },
        { name: '黄金 ETF', value: 40, percentage: 40 },
    ]);

    assert.deepEqual(items, [
        { name: '沪深 300 ETF', originalName: '沪深 300 ETF', value: 60, percentage: 60 },
        { name: '黄金 ETF', originalName: '黄金 ETF', value: 40, percentage: 40 },
    ]);
});

test('preserves single and empty asset distributions', async () => {
    assert.deepEqual(await buildItems([
        { name: '黄金 ETF', value: 100, percentage: 100 },
    ]), [
        { name: '黄金 ETF', originalName: '黄金 ETF', value: 100, percentage: 100 },
    ]);
    assert.deepEqual(await buildItems([]), []);
});
