import { expect, test, type Page } from '@playwright/test';

const sessionId = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
const expiresAt = '2026-12-31T23:59:59.000Z';

function previewResponse(revision: number, status: 'MAPPING_REQUIRED' | 'PREVIEW_READY', mapping: object | null, confirmable = false) {
    return { code: 200, message: 'ok', data: {
        importSessionId: sessionId, importBatchId: confirmable ? '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091' : null, sessionStatus: status, revision, detectedColumns: ['日期', '类型', '金额', '账户', '分类', '说明'], mapping, rows: [],
        summary: { totalRows: 2, validRows: 2, warningRows: 0, errorRows: 0, importableRows: 2, duplicateCandidates: 0 },
        fileDigest: 'file-digest', mappingDigest: null, optionsDigest: 'options-digest', normalizedRowsDigest: null, expiresAt, confirmable, previewToken: confirmable ? 'opaque-preview-token' : null,
    } };
}

const batchId = '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
function receipt() { return { importSessionId: sessionId, importBatchId: batchId, status: 'CONFIRMED', sourceFileName: 'demo.csv', fileDigest: 'file', totalRows: 1, acceptedRows: 1, createdCount: 1, skippedCount: 0, warningCount: 0, transactions: [{ rowNumber: 2, transactionId: 99 }], accountImpacts: [{ accountId: 1, rowCount: 1, balanceBefore: '9007199254740993.01', delta: '-0.01', balanceAfter: '9007199254740993.00' }], confirmedAt: '2026-08-25T08:00:00Z', contractVersion: 'v3', resultDigest: 'digest' }; }

test('unknown reload survives same-user 401 reauthentication and replays only the exact pending POST', async ({ page }) => {
    const idempotencyKey = 'd81c6c70-7c5d-4d36-ae45-fdd8f2a26a90';
    const body = '{"previewToken":"opaque","acknowledgedWarningIds":["warning-a"]}';
    const requests: Array<{ key: string | undefined; path: string; body: string }> = [];
    const recoverySequence: string[] = [];
    let confirmPosts = 0; let recoveryReceiptGets = 0; let receiptPageGets = 0;
    await page.addInitScript(({ importSessionId, importBatchId, bodyJson, idempotencyKey }) => {
        const storageKey = `finance-os:transaction-import:pending:v1:7:${idempotencyKey}`;
        if (localStorage.getItem(storageKey)) return;
        localStorage.setItem('token', 'test-token');
        localStorage.setItem('finance-os:auth-user-id:v1', '7');
        localStorage.setItem(storageKey, JSON.stringify({ schemaVersion: 1, userId: 7, state: 'OUTCOME_UNKNOWN', method: 'POST', path: `/imports/transactions/${importSessionId}/confirm`, importSessionId, importBatchId, idempotencyKey, bodyJson, submittedAt: '2026-08-25T08:00:00.000Z', updatedAt: '2026-08-25T08:00:00.000Z' }));
    }, { importSessionId: sessionId, importBatchId: batchId, bodyJson: body, idempotencyKey });
    await page.route('**/api/v1/login', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: { token: 'renewed-token', userId: 7 } }) }));
    await page.route(`**/api/v1/imports/transactions/batches/${batchId}`, route => {
        if (recoveryReceiptGets < 2) {
            recoveryReceiptGets += 1;
            recoverySequence.push('GET');
            return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 404, message: 'missing', errorCode: 'IMPORT_BATCH_NOT_FOUND' }) });
        }
        receiptPageGets += 1;
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: receipt() }) });
    });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/confirm`, route => {
        confirmPosts += 1;
        recoverySequence.push('POST');
        requests.push({ key: route.request().headers()['idempotency-key'], path: new URL(route.request().url()).pathname, body: route.request().postData() ?? '' });
        if (confirmPosts === 1) return route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'expired' }) });
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: { receipt: receipt(), idempotentReplay: true } }) });
    });
    await page.goto('/transactions/import');
    await expect(page.getByRole('heading', { name: '需要恢复确认结果' })).toBeVisible();
    await page.getByRole('button', { name: '查询并恢复原确认' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const pendingRecord = () => page.evaluate((storageKey: string) => JSON.parse(localStorage.getItem(storageKey) ?? '{}'), `finance-os:transaction-import:pending:v1:7:${idempotencyKey}`);
    await expect.poll(pendingRecord).toMatchObject({ state: 'AUTH_REQUIRED', resumeState: 'OUTCOME_UNKNOWN' });
    expect({ confirmPosts, recoveryReceiptGets, recoverySequence }).toEqual({ confirmPosts: 1, recoveryReceiptGets: 1, recoverySequence: ['GET', 'POST'] });
    await page.getByLabel('用户名').fill('same-user');
    await page.getByLabel('密码').fill('password');
    await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/\/transactions\/import$/);
    await page.getByRole('button', { name: '查询并恢复原确认' }).click();
    await expect(page).toHaveURL(new RegExp(`/transactions/import/receipts/${batchId}$`));
    await expect(page.getByRole('heading', { name: '权威导入回执' })).toBeVisible();
    expect({ confirmPosts, recoveryReceiptGets, recoverySequence }).toEqual({ confirmPosts: 2, recoveryReceiptGets: 2, recoverySequence: ['GET', 'POST', 'GET', 'POST'] });
    expect(receiptPageGets).toBeGreaterThanOrEqual(1);
    const expectedRequest = { key: idempotencyKey, path: `/api/v1/imports/transactions/${sessionId}/confirm`, body };
    expect(requests).toEqual([expectedRequest, expectedRequest]);
});

test('a user switch fails closed and never resumes the former user pending intent', async ({ page }) => {
    let confirmPosts = 0; let receiptGets = 0;
    await page.addInitScript(() => {
        if (sessionStorage.getItem('user-switch-fixture-initialized')) return;
        sessionStorage.setItem('user-switch-fixture-initialized', 'true');
        localStorage.setItem('token', 'user-a-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7');
        localStorage.setItem('finance-os:transaction-import:pending:v1:7:d81c6c70-7c5d-4d36-ae45-fdd8f2a26a90', JSON.stringify({ schemaVersion: 1, userId: 7, state: 'OUTCOME_UNKNOWN', method: 'POST', path: `/imports/transactions/${'9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091'}/confirm`, importSessionId: '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091', importBatchId: '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091', idempotencyKey: 'd81c6c70-7c5d-4d36-ae45-fdd8f2a26a90', bodyJson: '{"previewToken":"opaque","acknowledgedWarningIds":[]}', submittedAt: '2026-08-25T08:00:00.000Z', updatedAt: '2026-08-25T08:00:00.000Z' }));
        sessionStorage.setItem('finance-os:transaction-import:draft:v1:7', JSON.stringify({ schemaVersion: 1, userId: 7, activeStep: 'PREVIEW_READY', file: null, sessionId: '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091', batchId: '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091', sessionStatus: 'PREVIEW_READY', revision: 3, detectedColumns: [], mapping: null, summary: { totalRows: 1, validRows: 1, warningRows: 0, errorRows: 0, importableRows: 1, duplicateCandidates: 0 }, fileDigest: 'file', mappingDigest: null, optionsDigest: 'options', normalizedRowsDigest: null, expiresAt: '2026-12-31T23:59:59.000Z', previewToken: 'opaque', updatedAt: '2026-08-25T00:00:00.000Z' }));
    });
    await page.route('**/api/v1/login', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: { token: 'user-b-token', userId: 8 } }) }));
    await page.route(`**/api/v1/imports/transactions/${sessionId}/confirm`, route => { confirmPosts += 1; return route.abort(); });
    await page.route(`**/api/v1/imports/transactions/batches/${batchId}`, route => { receiptGets += 1; return route.abort(); });

    await page.goto('/transactions/import');
    await expect(page.getByRole('heading', { name: '需要恢复确认结果' })).toBeVisible();
    await page.goto('/login');
    await page.getByLabel('用户名').fill('user-b'); await page.getByLabel('密码').fill('password'); await page.getByRole('button', { name: '登录' }).click();
    await expect(page).toHaveURL(/\/investments$/);
    await page.goto('/transactions/import');
    await expect(page.getByRole('heading', { name: '需要恢复确认结果' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: '权威导入回执' })).toHaveCount(0);
    expect(await page.evaluate(() => ({ owner: localStorage.getItem('finance-os:auth-user-id:v1'), pendingA: localStorage.getItem('finance-os:transaction-import:pending:v1:7:d81c6c70-7c5d-4d36-ae45-fdd8f2a26a90'), draftB: sessionStorage.getItem('finance-os:transaction-import:draft:v1:8') }))).toEqual({ owner: '8', pendingA: expect.any(String), draftB: null });
    expect({ confirmPosts, receiptGets }).toEqual({ confirmPosts: 0, receiptGets: 0 });
});

async function installFullImportFlow(page: Page) {
    let uploads = 0; let mappings = 0; let rows = 0; let confirms = 0; let receiptGets = 0;
    const mappingSessions: string[] = []; const mapping = { columnMappings: { date: '日期', type: '类型', amount: '金额', account: '账户', category: '分类', description: '说明' }, typeMappings: { 支出: 'EXPENSE' }, accountMappings: { 现金: 1 }, categoryMappings: { 餐饮: 8 } };
    const ready = () => ({ ...previewResponse(3, 'PREVIEW_READY', mapping, true), data: { ...previewResponse(3, 'PREVIEW_READY', mapping, true).data, summary: { totalRows: 1, validRows: 1, warningRows: 1, errorRows: 0, importableRows: 1, duplicateCandidates: 1 } } });
    await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); localStorage.setItem('finance-os:auth-user-id:v1', '7'); });
    await page.route('**/api/v1/accounts', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ id: 1, name: '现金账户', type: 'CASH', status: 'ACTIVE', currency: 'CNY' }] }) }));
    await page.route('**/api/v1/categories', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: [{ id: 8, name: '餐饮', type: 'EXPENSE' }] }) }));
    await page.route('**/api/v1/imports/transactions/preview', route => { uploads += 1; return route.fulfill({ contentType: 'application/json', body: JSON.stringify(previewResponse(1, 'MAPPING_REQUIRED', null)) }); });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/preview`, route => { mappings += 1; mappingSessions.push(new URL(route.request().url()).pathname.split('/')[5] ?? ''); return route.fulfill({ contentType: 'application/json', body: JSON.stringify(mappings === 1 ? previewResponse(2, 'PREVIEW_READY', mapping) : ready()) }); });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/rows?*`, route => {
        rows += 1;
        return route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
                code: 200, message: 'ok', data: [{
                    rowNumber: 2, sourceValues: { 日期: '2026-08-01', 类型: '支出', 金额: '10.00', 账户: '现金', 分类: '餐饮', 说明: '<img src=x>' },
                    normalizedValues: { amount: '10.00', description: '<img src=x>' }, mappingStatus: 'MAPPED', errors: [],
                    warnings: [{ id: 'backend-warning-1', code: 'DATABASE_PROBABLE', scope: 'ROW', field: null, rowNumber: 2, message: '<script>server-warning</script>', retryable: false }],
                    duplicateStatus: 'DATABASE_PROBABLE', importable: true,
                }],
            }),
        });
    });
    await page.route(`**/api/v1/imports/transactions/${sessionId}/confirm`, route => { confirms += 1; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: { receipt: receipt(), idempotentReplay: false } }) }); });
    await page.route(`**/api/v1/imports/transactions/batches/${batchId}`, route => { receiptGets += 1; return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'ok', data: receipt() }) }); });
    return { counts: () => ({ uploads, mappings, rows, confirms, receiptGets, mappingSessions }) };
}

test('CSV full workflow preserves one mapping Session, explicit warning acknowledgement, Confirm, Receipt and reload', async ({ page }) => {
    const flow = await installFullImportFlow(page);
    await page.goto('/transactions/import');
    await page.getByLabel('选择 CSV 或 XLSX 文件').setInputFiles({ name: 'closing.csv', mimeType: 'text/csv', buffer: Buffer.from('日期,类型,金额,账户,分类,说明\n2026-08-01,支出,10,现金,餐饮,午餐') });
    await page.getByRole('button', { name: '上传并开始映射' }).click();
    for (const target of ['日期', '类型', '金额', '账户', '分类', '说明']) await page.getByLabel(`${target}来源列`).selectOption(target);
    await page.getByRole('button', { name: '保存列映射并发现来源值' }).click();
    await page.getByLabel('类型 支出').selectOption('EXPENSE'); await page.getByLabel('账户 现金').selectOption('1'); await page.getByLabel('分类 餐饮').selectOption('8');
    await page.getByRole('button', { name: '提交正式映射并生成预览' }).click();
    await expect(page.getByText('<script>server-warning</script>').first()).toBeVisible();
    await expect(page.getByText('Ready to confirm')).toHaveCount(0);
    await page.getByRole('checkbox').check();
    await expect(page.getByText('Ready to confirm')).toBeVisible();
    await page.getByRole('button', { name: '打开最终确认' }).click(); await page.getByRole('button', { name: '确认导入 1 条流水' }).click();
    await expect(page).toHaveURL(new RegExp(`/transactions/import/receipts/${batchId}$`));
    await expect(page.getByRole('heading', { name: '权威导入回执' })).toBeVisible();
    const receiptGetsBeforeReload = flow.counts().receiptGets;
    await page.reload();
    await expect(page.getByRole('heading', { name: '权威导入回执' })).toBeVisible();
    const result = flow.counts();
    expect(result.uploads).toBe(1); expect(result.mappings).toBe(2); expect(result.mappingSessions).toEqual([sessionId, sessionId]); expect(result.rows).toBeGreaterThanOrEqual(3); expect(result.confirms).toBe(1); expect(result.receiptGets).toBeGreaterThan(receiptGetsBeforeReload);
});
