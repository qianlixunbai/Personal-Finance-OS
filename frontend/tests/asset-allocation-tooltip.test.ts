import assert from 'node:assert/strict';
import test from 'node:test';
import { buildAssetAllocationData } from '../src/components/charts/assetAllocationData.ts';
import { formatAssetAllocationTooltip } from '../src/components/charts/assetAllocationTooltip.ts';

const marker = '<span style="background-color:#22d3ee"></span>';
const cases = [
    ['普通中文资产', '普通中文资产'],
    ['English asset', 'English asset'],
    ['<b>标签</b>', '&lt;b&gt;标签&lt;/b&gt;'],
    ['& < > " \'', '&amp; &lt; &gt; &quot; &#39;'],
    ['<img src=x onerror="window.__financeTooltipXss=true">', '&lt;img src=x onerror=&quot;window.__financeTooltipXss=true&quot;&gt;'],
    ['&lt;img&gt; &amp;', '&amp;lt;img&amp;gt; &amp;amp;'],
];

for (const [name, encodedName] of cases) {
    test(`HTML tooltip encodes the name and preserves layout: ${name}`, () => {
        const [data] = buildAssetAllocationData([{ name, value: 1234.56, percentage: 12.34 }]);
        assert.equal(data.name, name);
        assert.equal(data.originalName, name);
        assert.equal(formatAssetAllocationTooltip({ marker, data }), `${marker}${encodedName}<br/>市值：¥1,234.56<br/>占比：12.34%`);
        assert.equal(formatAssetAllocationTooltip({ data }), `${encodedName}<br/>市值：¥1,234.56<br/>占比：12.34%`);
    });
}

test('duplicate malicious names are disambiguated then encoded without changing data', () => {
    const name = '<img src=x onerror="window.__financeTooltipXss=true">';
    const data = buildAssetAllocationData([{ name, value: 2, percentage: 66.67 }, { name, value: 1, percentage: 33.33 }]);
    assert.deepEqual(data.map(item => [item.name, item.originalName, item.value, item.percentage]), [
        [`${name}（1）`, name, 2, 66.67], [`${name}（2）`, name, 1, 33.33],
    ]);
    for (const [index, item] of data.entries()) {
        const html = formatAssetAllocationTooltip({ data: item });
        assert.ok(html.startsWith(`&lt;img src=x onerror=&quot;window.__financeTooltipXss=true&quot;&gt;（${index + 1}）<br/>`));
        assert.ok(!html.includes('<img'));
    }
});

test('empty allocation stays empty', () => {
    assert.deepEqual(buildAssetAllocationData([]), []);
});
