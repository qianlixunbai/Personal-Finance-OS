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
    await expect(page.getByRole('heading', { name: '服务端预览' })).toBeVisible();
    await expect(page.getByText('Ready to confirm')).toBeVisible();
    expect({ uploadCount, mappingCount, confirmCount }).toEqual({ uploadCount: 1, mappingCount: 2, confirmCount: 0 });
    expect(rowsCount).toBeGreaterThanOrEqual(3);
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

test('warning acknowledgement is explicit and still makes zero confirm requests', async ({ page }) => {
    let confirmCount = 0;
    await page.setViewportSize({ width: 375, height: 812 });
    await page.addInitScript(({ expiresAt: snapshotExpiry, sessionId: snapshotSessionId }) => {
        localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7');
        sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: { name: 'demo.csv', size: 10, type: 'text/csv' }, sessionId: snapshotSessionId, batchId: null, sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }, summary: { totalRows: 2, validRows: 2, warningRows: 2, errorRows: 0, importableRows: 2, duplicateCandidates: 2 }, fileDigest: 'file', mappingDigest: 'mapping', optionsDigest: 'options', normalizedRowsDigest: 'rows', expiresAt: snapshotExpiry, previewToken: 'opaque', updatedAt: '2026-08-24T00:00:00.000Z' }));
    }, { expiresAt, sessionId });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [
        { rowNumber: 2, sourceValues: { 说明: '<img src=x onerror=alert(1)>' }, normalizedValues: { amount: '10.00', description: '<img src=x onerror=alert(1)>' }, mappingStatus: 'MAPPED', errors: [], warnings: [{ id: 'backend-warning-1', code: 'IN_FILE_PROBABLE', scope: 'ROW', field: null, rowNumber: 2, message: '<script>bad()</script>', retryable: false }], duplicateStatus: 'IN_FILE_PROBABLE', importable: true },
        { rowNumber: 3, sourceValues: {}, normalizedValues: { amount: '12.00' }, mappingStatus: 'MAPPED', errors: [], warnings: [{ id: 'backend-warning-2', code: 'DATABASE_PROBABLE', scope: 'ROW', field: null, rowNumber: 3, message: '疑似重复', retryable: false }], duplicateStatus: 'DATABASE_PROBABLE', importable: true },
    ] }) }));
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import');
    await expect(page.getByRole('heading', { name: '警告复核' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '问题' })).toBeVisible();
    await expect(page.getByText('<script>bad()</script>').first()).toBeVisible();
    await expect(page.getByText('Ready to confirm')).toHaveCount(0);
    const checks = page.getByRole('checkbox');
    await checks.nth(0).focus(); await page.keyboard.press('Space');
    await expect(checks.nth(0)).toBeChecked(); await checks.nth(1).check();
    await expect(page.getByText('Ready to confirm')).toBeVisible();
    expect(await page.locator('.import-preview-summary').evaluate(element => element.getBoundingClientRect().right <= window.innerWidth + 1)).toBe(true);
    expect(await page.locator('.import-table-scroll').evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true);
    expect(confirmCount).toBe(0);
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

test('a direct import visit without a usable snapshot makes no Preview or Confirm request', async ({ page }) => {
    let rowsCount = 0; let confirmCount = 0;
    await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7'); });
    await page.route('**/api/v1/imports/transactions/**/rows?*', route => { rowsCount += 1; return route.abort(); });
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import');
    await expect(page.getByRole('alert').filter({ hasText: '没有可恢复的导入草稿' })).toBeVisible();
    await expect(page.getByLabel('选择 CSV 或 XLSX 文件')).toBeEnabled();
    expect({ rowsCount, confirmCount }).toEqual({ rowsCount: 0, confirmCount: 0 });
});

test('a late old-revision hydration page cannot overwrite the next revision warning state', async ({ page }) => {
    let revisionTwo = false; let confirmCount = 0; let releaseOldPage!: () => void; let oldPageStarted!: () => void;
    const oldPage = new Promise<void>(resolve => { releaseOldPage = resolve; }); const oldStarted = new Promise<void>(resolve => { oldPageStarted = resolve; });
    const rows = (count: number) => Array.from({ length: count }, (_, index) => ({ rowNumber: index + 2, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true }));
    await page.addInitScript(({ snapshotExpiry, snapshotSessionId }) => {
        localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7');
        sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: { name: 'demo.csv', size: 10, type: 'text/csv' }, sessionId: snapshotSessionId, batchId: null, sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }, summary: { totalRows: 501, validRows: 501, warningRows: 1, errorRows: 0, importableRows: 501, duplicateCandidates: 1 }, fileDigest: 'file', mappingDigest: 'mapping', optionsDigest: 'options', normalizedRowsDigest: 'rows', expiresAt: snapshotExpiry, previewToken: 'opaque', updatedAt: '2026-08-24T00:00:00.000Z' }));
    }, { snapshotExpiry: expiresAt, snapshotSessionId: sessionId });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}`, route => route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: 'unresolved' }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/preview`, route => { revisionTwo = true; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ ...previewResponse(4, 'PREVIEW_READY', { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }), data: { ...previewResponse(4, 'PREVIEW_READY', { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }).data, summary: { totalRows: 1, validRows: 1, warningRows: 1, errorRows: 0, importableRows: 1, duplicateCandidates: 1 } } }) }); });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, async route => {
        const url = new URL(route.request().url()); const requestedPage = Number(url.searchParams.get('page')); const size = Number(url.searchParams.get('size'));
        if (revisionTwo) return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ rowNumber: 2, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [{ id: 'new-warning', code: 'DATABASE_PROBABLE', scope: 'ROW', field: null, rowNumber: 2, message: '新 revision 警告', retryable: false }], duplicateStatus: 'DATABASE_PROBABLE', importable: true }] }) });
        if (requestedPage === 2 && size === 500) { oldPageStarted(); await oldPage; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ rowNumber: 502, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [{ id: 'old-warning', code: 'IN_FILE_PROBABLE', scope: 'ROW', field: null, rowNumber: 502, message: '旧 revision 警告', retryable: false }], duplicateStatus: 'IN_FILE_PROBABLE', importable: true }] }) }); }
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: rows(size) }) });
    });
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import'); await oldStarted;
    await page.getByRole('button', { name: '放弃当前导入' }).click();
    await page.getByRole('button', { name: '重新生成预览' }).click();
    await expect(page.getByText('新 revision 警告').first()).toBeVisible();
    await page.getByRole('checkbox').check(); await expect(page.getByText('Ready to confirm')).toBeVisible();
    releaseOldPage();
    await expect(page.getByText('旧 revision 警告')).toHaveCount(0);
    await expect(page.getByText('Ready to confirm')).toBeVisible();
    expect(confirmCount).toBe(0);
});

test('10k Preview hydration scans twenty 500-row pages while retaining only the visible page', async ({ page }) => {
    let rowsCount = 0; let confirmCount = 0;
    const rows = (count: number) => Array.from({ length: count }, (_, index) => ({ rowNumber: index + 2, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true }));
    await page.addInitScript(({ snapshotExpiry, snapshotSessionId }) => {
        localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7');
        sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: { name: '10k.csv', size: 10, type: 'text/csv' }, sessionId: snapshotSessionId, batchId: null, sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }, summary: { totalRows: 10000, validRows: 10000, warningRows: 0, errorRows: 0, importableRows: 10000, duplicateCandidates: 0 }, fileDigest: 'file', mappingDigest: 'mapping', optionsDigest: 'options', normalizedRowsDigest: 'rows', expiresAt: snapshotExpiry, previewToken: 'opaque', updatedAt: '2026-08-24T00:00:00.000Z' }));
    }, { snapshotExpiry: expiresAt, snapshotSessionId: sessionId });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => { rowsCount += 1; const size = Number(new URL(route.request().url()).searchParams.get('size')); return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: rows(size) }) }); });
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import');
    await expect(page.getByText('Ready to confirm')).toBeVisible();
    await expect(page.locator('.import-preview-table tbody tr')).toHaveCount(100);
    expect(rowsCount).toBe(21);
    expect(confirmCount).toBe(0);
});

test('Preview GET resumes from the same-user draft after the existing 401 login flow', async ({ page }) => {
    let rowsCount = 0; let mappingCount = 0; let confirmCount = 0;
    await page.addInitScript(({ snapshotExpiry, snapshotSessionId }) => {
        localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7');
        sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: { name: 'demo.csv', size: 10, type: 'text/csv' }, sessionId: snapshotSessionId, batchId: null, sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }, summary: { totalRows: 1, validRows: 1, warningRows: 0, errorRows: 0, importableRows: 1, duplicateCandidates: 0 }, fileDigest: 'file', mappingDigest: 'mapping', optionsDigest: 'options', normalizedRowsDigest: 'rows', expiresAt: snapshotExpiry, previewToken: 'opaque', updatedAt: '2026-08-24T00:00:00.000Z' }));
    }, { snapshotExpiry: expiresAt, snapshotSessionId: sessionId });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/login', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: { token: 'renewed-token', userId: 7 } }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/preview`, route => { mappingCount += 1; return route.abort(); });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => { rowsCount += 1; return rowsCount === 1 ? route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'expired' }) }) : route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ rowNumber: 2, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true }] }) }); });
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('用户名').fill('same-user'); await page.getByLabel('密码').fill('password'); await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/\/investments$/);
    await page.goto('/transactions/import');
    await expect(page.getByRole('heading', { name: '服务端预览' })).toBeVisible();
    expect(rowsCount).toBeGreaterThanOrEqual(2);
    expect({ mappingCount, confirmCount }).toEqual({ mappingCount: 0, confirmCount: 0 });
});

test('hydration restarts after same-user 401 and reaches the full backend warning set', async ({ page }) => {
    let pageTwoUnauthorized = true; let mappingCount = 0; let confirmCount = 0;
    const validRows = (count: number) => Array.from({ length: count }, (_, index) => ({ rowNumber: index + 2, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true }));
    await page.addInitScript(({ snapshotExpiry, snapshotSessionId }) => {
        localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7');
        sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: { name: 'demo.csv', size: 10, type: 'text/csv' }, sessionId: snapshotSessionId, batchId: null, sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }, summary: { totalRows: 501, validRows: 501, warningRows: 1, errorRows: 0, importableRows: 501, duplicateCandidates: 1 }, fileDigest: 'file', mappingDigest: 'mapping', optionsDigest: 'options', normalizedRowsDigest: 'rows', expiresAt: snapshotExpiry, previewToken: 'opaque', updatedAt: '2026-08-24T00:00:00.000Z' }));
    }, { snapshotExpiry: expiresAt, snapshotSessionId: sessionId });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/login', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: { token: 'renewed-token', userId: 7 } }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/preview`, route => { mappingCount += 1; return route.abort(); });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => {
        const url = new URL(route.request().url()); const requestedPage = Number(url.searchParams.get('page')); const size = Number(url.searchParams.get('size'));
        if (requestedPage === 2 && size === 500 && pageTwoUnauthorized) { pageTwoUnauthorized = false; return route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'expired' }) }); }
        const data = requestedPage === 2 ? [{ rowNumber: 502, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [{ id: 'backend-warning-501', code: 'DATABASE_PROBABLE', scope: 'ROW', field: null, rowNumber: 502, message: '疑似重复', retryable: false }], duplicateStatus: 'DATABASE_PROBABLE', importable: true }] : validRows(size);
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data }) });
    });
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('用户名').fill('same-user'); await page.getByLabel('密码').fill('password'); await page.getByRole('button', { name: '登录' }).click();
    await page.goto('/transactions/import');
    await expect(page.getByRole('checkbox', { name: /我已复核.*1 项警告/ })).toBeVisible();
    expect({ mappingCount, confirmCount }).toEqual({ mappingCount: 0, confirmCount: 0 });
});

test('Cancel 409 discards the draft so a reload cannot resume an unresolved session', async ({ page }) => {
    let rowsCount = 0; let confirmCount = 0;
    await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7'); });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => { rowsCount += 1; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ rowNumber: 2, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true }] }) }); });
    await page.route(`**/api/v1/imports/transactions/${sessionId}`, route => route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: 'cannot determine terminal state', retryable: false }) }));
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import');
    await page.evaluate(({ snapshotExpiry, snapshotSessionId }) => sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: { name: 'demo.csv', size: 10, type: 'text/csv' }, sessionId: snapshotSessionId, batchId: null, sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }, summary: { totalRows: 1, validRows: 1, warningRows: 0, errorRows: 0, importableRows: 1, duplicateCandidates: 0 }, fileDigest: 'file', mappingDigest: 'mapping', optionsDigest: 'options', normalizedRowsDigest: 'rows', expiresAt: snapshotExpiry, previewToken: 'opaque', updatedAt: '2026-08-24T00:00:00.000Z' })), { snapshotExpiry: expiresAt, snapshotSessionId: sessionId });
    await page.reload();
    await expect(page.getByRole('heading', { name: '服务端预览' })).toBeVisible();
    await page.getByRole('button', { name: '放弃当前导入' }).click();
    await expect(page.getByRole('heading', { name: '需要重新生成预览' })).toBeVisible();
    await expect.poll(() => page.evaluate(() => sessionStorage.getItem('finance-os:transaction-import:draft:v1:7'))).toBeNull();
    const beforeReload = rowsCount;
    await page.reload();
    await expect(page.getByRole('alert').filter({ hasText: '没有可恢复的导入草稿' })).toBeVisible();
    expect(rowsCount).toBe(beforeReload);
    expect(confirmCount).toBe(0);
});

test('successful Cancel clears Preview evidence and never creates a Confirm request', async ({ page }) => {
    let confirmCount = 0;
    await page.addInitScript(({ snapshotExpiry, snapshotSessionId }) => {
        localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7');
        sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: { name: 'demo.csv', size: 10, type: 'text/csv' }, sessionId: snapshotSessionId, batchId: null, sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: { columnMappings: {}, typeMappings: {}, accountMappings: {}, categoryMappings: {} }, summary: { totalRows: 1, validRows: 1, warningRows: 0, errorRows: 0, importableRows: 1, duplicateCandidates: 0 }, fileDigest: 'file', mappingDigest: 'mapping', optionsDigest: 'options', normalizedRowsDigest: 'rows', expiresAt: snapshotExpiry, previewToken: 'opaque', updatedAt: '2026-08-24T00:00:00.000Z' }));
    }, { snapshotExpiry: expiresAt, snapshotSessionId: sessionId });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [] }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ rowNumber: 2, sourceValues: {}, normalizedValues: {}, mappingStatus: 'MAPPED', errors: [], warnings: [], duplicateStatus: 'NONE', importable: true }] }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}`, route => route.fulfill({ status: 204 }));
    page.on('request', request => { if (new URL(request.url()).pathname.includes('/confirm')) confirmCount += 1; });
    await page.goto('/transactions/import'); await expect(page.getByRole('heading', { name: '服务端预览' })).toBeVisible();
    await page.getByRole('button', { name: '放弃当前导入' }).click();
    await expect(page.getByText('当前导入已取消。')).toBeVisible();
    await expect(page.getByRole('heading', { name: '服务端预览' })).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => sessionStorage.getItem('finance-os:transaction-import:draft:v1:7'))).toBeNull();
    expect(confirmCount).toBe(0);
});

for (const [label, message, expected] of [['过期', 'IMPORT_PREVIEW_EXPIRED', '导入预览已过期'], ['已取消', 'IMPORT_SESSION_CANCELLED', '导入会话已取消'], ['陈旧', 'IMPORT_PREVIEW_STALE', '导入预览已陈旧']] as const) {
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
        await expect(page.getByRole('alert').filter({ hasText: expected })).toBeVisible();
        expect(confirmCount).toBe(0);
    });
}
