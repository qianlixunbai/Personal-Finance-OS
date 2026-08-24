import { expect, test } from '@playwright/test';

const sessionId = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
const expiresAt = '2026-12-31T23:59:59.000Z';

function previewResponse(revision: number, status: 'MAPPING_REQUIRED' | 'PREVIEW_READY', mapping: object | null, confirmable = false) {
    return { code: 200, message: 'ok', data: {
        importSessionId: sessionId, importBatchId: null, sessionStatus: status, revision, detectedColumns: ['日期', '类型', '金额', '账户', '分类', '说明'], mapping, rows: [],
        summary: { totalRows: 2, validRows: 2, warningRows: 0, errorRows: 0, importableRows: 2, duplicateCandidates: 0 },
        fileDigest: 'file-digest', mappingDigest: null, optionsDigest: 'options-digest', normalizedRowsDigest: null, expiresAt, confirmable, previewToken: confirmable ? 'opaque-preview-token' : null,
    } };
}

test('CSV upload uses one session for discovery and formal mapping without a financial confirm request', async ({ page }) => {
    let uploadCount = 0; let mappingCount = 0; let rowsCount = 0; let confirmCount = 0;
    await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7'); });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ id: 1, name: '现金账户', type: 'CASH', status: 'ACTIVE', currency: 'CNY' }] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ id: 8, name: '餐饮', type: 'EXPENSE' }] }) }));
    await page.route('**/api/v1/imports/transactions/preview', route => { uploadCount += 1; return route.fulfill({ contentType: 'application/json', body: JSON.stringify(previewResponse(1, 'MAPPING_REQUIRED', null)) }); });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/preview`, route => {
        mappingCount += 1;
        const mapping = mappingCount === 1
            ? { columnMappings: { date: '日期', type: '类型', amount: '金额', account: '账户', category: '分类', description: '说明' }, typeMappings: {}, accountMappings: {}, categoryMappings: {} }
            : { columnMappings: { date: '日期', type: '类型', amount: '金额', account: '账户', category: '分类', description: '说明' }, typeMappings: { 支出: 'EXPENSE' }, accountMappings: { 现金: 1 }, categoryMappings: { 餐饮: 8 } };
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify(previewResponse(mappingCount + 1, 'PREVIEW_READY', mapping, mappingCount === 2)) });
    });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => { rowsCount += 1; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [
        { rowNumber: 2, sourceValues: { 日期: '2026-08-01', 类型: '支出', 金额: '10.00', 账户: '现金', 分类: '餐饮', 说明: '午餐' }, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true },
        { rowNumber: 3, sourceValues: { 日期: '2026-08-02', 类型: '支出', 金额: '12.00', 账户: '现金', 分类: '餐饮', 说明: '晚餐' }, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true },
    ] }) }); });
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });

    await page.goto('/transactions/import');
    await page.getByLabel('选择 CSV 或 XLSX 文件').setInputFiles({ name: 'demo.csv', mimeType: 'text/csv', buffer: Buffer.from('日期,类型,金额,账户,分类,说明\n2026-08-01,支出,10,现金,餐饮,午餐') });
    await page.getByRole('button', { name: '上传并开始映射' }).click();
    await expect(page.getByText('文件已由服务端接收，请完成字段映射。')).toBeVisible();
    for (const [target, source] of [['日期', '日期'], ['类型', '类型'], ['金额', '金额'], ['账户', '账户'], ['分类', '分类'], ['说明', '说明']] as const) await page.getByLabel(`${target}来源列`).selectOption(source);
    await page.getByRole('button', { name: '保存列映射并发现来源值' }).click();
    await expect(page.getByLabel('类型 支出')).toBeVisible();
    await page.getByLabel('类型 支出').selectOption('EXPENSE');
    await page.getByLabel('账户 现金').selectOption('1');
    await page.getByLabel('分类 餐饮').selectOption('8');
    await page.getByRole('button', { name: '提交正式映射并生成预览' }).click();
    await expect(page.getByRole('heading', { name: '已到达预览边界' })).toBeVisible();
    expect({ uploadCount, mappingCount, rowsCount, confirmCount }).toEqual({ uploadCount: 1, mappingCount: 2, rowsCount: 1, confirmCount: 0 });
});

test('XLSX selection reaches the same server-owned mapping state without a confirm request', async ({ page }) => {
    let uploadCount = 0; let confirmCount = 0;
    await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7'); });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/imports/transactions/preview', route => { uploadCount += 1; return route.fulfill({ contentType: 'application/json', body: JSON.stringify(previewResponse(1, 'MAPPING_REQUIRED', null)) }); });
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import');
    const fileInput = page.getByLabel('选择 CSV 或 XLSX 文件');
    await expect(fileInput).toHaveAttribute('accept', '.csv,.xlsx');
    await fileInput.setInputFiles({ name: 'demo.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('xlsx-fixture') });
    await page.getByRole('button', { name: '上传并开始映射' }).click();
    await expect(page.getByRole('heading', { name: '映射字段' })).toBeVisible();
    expect({ uploadCount, confirmCount }).toEqual({ uploadCount: 1, confirmCount: 0 });
});

test('file selection supports replace and clear, and blocks an oversized local file before upload', async ({ page }) => {
    let uploadCount = 0;
    await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7'); });
    await page.route('**/api/v1/imports/transactions/preview', route => { uploadCount += 1; return route.abort(); });
    await page.goto('/transactions/import');
    const fileInput = page.getByLabel('选择 CSV 或 XLSX 文件');
    await fileInput.setInputFiles({ name: 'first.csv', mimeType: 'text/csv', buffer: Buffer.from('one') });
    await expect(page.getByText('first.csv')).toBeVisible();
    await fileInput.setInputFiles({ name: 'replacement.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: Buffer.from('two') });
    await expect(page.getByText('replacement.xlsx')).toBeVisible();
    await page.getByRole('button', { name: '清除' }).click();
    await expect(page.getByText('replacement.xlsx')).toHaveCount(0);
    await fileInput.setInputFiles({ name: 'oversized.csv', mimeType: 'text/csv', buffer: Buffer.alloc(5 * 1024 * 1024 + 1) });
    await page.getByRole('button', { name: '上传并开始映射' }).click();
    await expect(page.getByRole('alert').filter({ hasText: '文件不能超过 5 MiB' })).toBeVisible();
    expect(uploadCount).toBe(0);
});

for (const [label, message] of [['过期', 'IMPORT_PREVIEW_EXPIRED'], ['已取消', 'IMPORT_SESSION_CANCELLED'], ['陈旧', 'IMPORT_PREVIEW_STALE']] as const) {
    test(`mapping ${label} Session 展示服务端安全错误且零 Confirm`, async ({ page }) => {
        let confirmCount = 0;
        await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7'); });
        await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
        await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
        await page.route('**/api/v1/imports/transactions/preview', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(previewResponse(1, 'MAPPING_REQUIRED', null)) }));
        await page.route(`**/api/v1/imports/transactions/${sessionId}/preview`, route => route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message, errorCode: message, retryable: false }) }));
        page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
        await page.goto('/transactions/import');
        await page.getByLabel('选择 CSV 或 XLSX 文件').setInputFiles({ name: 'demo.csv', mimeType: 'text/csv', buffer: Buffer.from('x') });
        await page.getByRole('button', { name: '上传并开始映射' }).click();
        for (const [target, source] of [['日期', '日期'], ['类型', '类型'], ['金额', '金额'], ['账户', '账户'], ['分类', '分类'], ['说明', '说明']] as const) await page.getByLabel(`${target}来源列`).selectOption(source);
        await page.getByRole('button', { name: '保存列映射并发现来源值' }).click();
        await expect(page.getByRole('alert').filter({ hasText: message })).toBeVisible();
        expect(confirmCount).toBe(0);
    });
}
