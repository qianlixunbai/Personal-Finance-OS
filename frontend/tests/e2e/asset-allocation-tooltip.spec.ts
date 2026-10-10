import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { writeFile } from 'node:fs/promises';

interface Allocation { name: string; value: number; percentage: number }
const payload = '<img src=x onerror="window.__financeTooltipXss=true">';
const currency = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });

async function openDashboard(page: Page, entries: Allocation[]) {
    await page.addInitScript(() => {
        localStorage.setItem('token', 'tooltip-isolated-test-token');
        localStorage.setItem('finance-os:auth-user-id:v1', '7');
        (window as Window & { __financeTooltipXss?: boolean }).__financeTooltipXss = false;
    });
    // Fail closed for all API calls: this suite never reaches a real backend or database.
    await page.route('**/api/v1/**', route => {
        if (new URL(route.request().url()).pathname !== '/api/v1/dashboard') return route.abort();
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: {
            totalAssets: 1000, netWorth: 1000, monthIncome: 0, monthExpense: 0, monthNet: 0,
            assetAllocation: entries, monthlyCashFlowTrend: [], recentTransactions: [],
        } }) });
    });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await expect(page.getByRole('heading', { name: '投资资产分布', exact: true })).toBeVisible();
}

async function hoverSlice(page: Page, slices: Allocation[], index: number) {
    const chart = page.getByRole('img', { name: '投资资产分布环形图', exact: true });
    await chart.scrollIntoViewIfNeeded();
    await expect(chart.locator('canvas')).toBeVisible();
    const box = await chart.boundingBox();
    if (!box) throw new Error('Allocation chart has no browser bounds');
    const total = slices.reduce((sum, item) => sum + item.value, 0);
    const preceding = slices.slice(0, index).reduce((sum, item) => sum + item.value, 0);
    const angle = 2 * Math.PI * (preceding + slices[index].value / 2) / total;
    const radius = Math.min(box.width, box.height) * 0.30;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height * 0.42);
    // Real pointer movement over the canvas; do not dispatch ECharts showTip actions.
    await page.mouse.move(box.x + box.width / 2 + Math.sin(angle) * radius, box.y + box.height * 0.42 - Math.cos(angle) * radius);
    const tooltip = chart.locator('div').filter({ hasText: '市值：' }).last();
    await expect(tooltip).toBeVisible();
    await expect(tooltip).toContainText(`市值：${currency.format(slices[index].value)}`);
    return tooltip;
}

async function assertSafeTooltip(page: Page, slices: Allocation[], index: number, info: TestInfo) {
    const tooltip = await hoverSlice(page, slices, index);
    const snapshot = await tooltip.evaluate(element => ({ html: element.innerHTML, text: element.textContent }));
    const executed = await page.evaluate(() => (window as Window & { __financeTooltipXss?: boolean }).__financeTooltipXss);
    const evidencePath = info.outputPath(`hover-${index}-${info.attachments.length}.json`);
    await writeFile(evidencePath, JSON.stringify({ expected: slices[index], ...snapshot, executed }, null, 2));
    await info.attach('hover evidence', { path: evidencePath, contentType: 'application/json' });
    expect(executed).toBe(false);
    await expect(tooltip.locator('img, script, svg, iframe, object, b, [onerror], [onload]')).toHaveCount(0);
    const text = await tooltip.textContent();
    expect(text).toBe(`${slices[index].name}市值：${currency.format(slices[index].value)}占比：${slices[index].percentage.toFixed(2)}%`);
    await expect(tooltip.locator('br')).toHaveCount(2);
    await expect(tooltip.locator('span').first()).toBeVisible();
}

const nameGroups = [
    { label: 'Chinese English HTML and special characters', names: ['中文资产', 'English asset', '<b>标签文本</b>', 'A & B < C > D "双引号" \'单引号\''] },
    { label: 'event-handler payloads and literal entities', names: [payload, '<svg onload="window.__financeTooltipXss=true"></svg>', '&lt;img src=x&gt; &amp;', '正常资产 after payload'] },
];

for (const group of nameGroups) {
    test(`safe hover and consecutive switching: ${group.label}`, async ({ page }, info) => {
        const entries = group.names.map((name, index) => ({ name, value: (4 - index) * 100, percentage: (4 - index) * 10 }));
        await openDashboard(page, entries);
        for (const index of [0, 1, 2, 3, 0]) await assertSafeTooltip(page, entries, index, info);
        await page.screenshot({ path: info.outputPath('tooltip.png'), fullPage: true });
        await info.attach('tooltip screenshot', { path: info.outputPath('tooltip.png'), contentType: 'image/png' });
    });
}

test('duplicate malicious names and the Other slice preserve names amounts and percentages', async ({ page }, info) => {
    const entries = [
        { name: payload, value: 350, percentage: 35 }, { name: payload, value: 250, percentage: 25 },
        { name: '普通资产', value: 200, percentage: 20 }, { name: 'English', value: 100, percentage: 10 },
        { name: '<b>第五资产</b>', value: 60, percentage: 6 }, { name: '第六资产 &', value: 40, percentage: 4 },
    ];
    await openDashboard(page, entries);
    const slices = [
        { ...entries[0], name: `${payload}（1）` }, { ...entries[1], name: `${payload}（2）` },
        entries[2], entries[3], { name: '其他', value: 100, percentage: 10 },
    ];
    for (let index = 0; index < slices.length; index += 1) await assertSafeTooltip(page, slices, index, info);
    await expect(page.getByRole('list', { name: '投资资产类别排名' }).getByText('其他 2 类')).toBeVisible();
});

for (const entries of [[], [{ name: payload, value: 0, percentage: 0 }, { name: '负值', value: -1, percentage: 0 }]]) {
    test(`empty allocation with ${entries.length} input entries`, async ({ page }) => {
        await openDashboard(page, entries);
        await expect(page.getByText('添加资产后，这里将显示投资配置。')).toBeVisible();
        await expect(page.getByRole('link', { name: '前往资产', exact: true })).toBeVisible();
        await expect(page.getByRole('img', { name: '投资资产分布环形图', exact: true })).toHaveCount(0);
        expect(await page.evaluate(() => (window as Window & { __financeTooltipXss?: boolean }).__financeTooltipXss)).toBe(false);
    });
}

test('single asset hover remains functional after desktop narrow and wide resizing', async ({ page }, info) => {
    const entries = [{ name: '中文 English & < > " \'', value: 1234.56, percentage: 100 }];
    await openDashboard(page, entries);
    for (const width of [1280, 390, 2400]) {
        await page.setViewportSize({ width, height: 900 });
        await assertSafeTooltip(page, entries, 0, info);
        const tooltip = page.getByRole('img', { name: '投资资产分布环形图', exact: true }).locator('div').filter({ hasText: '市值：' }).last();
        await expect(tooltip).toHaveCSS('font-size', width >= 2200 ? '13px' : '12px');
    }
});
