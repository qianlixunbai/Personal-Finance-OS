import { expect, request, test, type APIRequestContext, type BrowserContext, type Page } from '@playwright/test';

const runId = `${Date.now()}-${Math.floor(Math.random() * 10000)}`;
let fixtureSequence = 0;
const username = `investment_e2e_${runId}`;
const password = 'Investment-E2E-2026!';
let api: APIRequestContext;
let token: string;
let userId: number;
let accountId: number;
let instrumentId: number;
let firstBuyAccountId: number;
let firstBuyInstrumentId: number;
let firstBuyConflictAccountId: number;
let firstBuyConflictInstrumentId: number;
let firstBuyKey: string;
const firstBuyBody = { quantity: '10', unitPrice: '100', feeAmount: '1', taxAmount: '0' };
const browserFailures = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }) => {
    const failures: string[] = [];
    browserFailures.set(page, failures);
    page.on('pageerror', error => failures.push(`pageerror: ${error.message}`));
    page.on('console', message => { if (message.type() === 'error') failures.push(`console.error: ${message.text()}`); });
});

test.afterEach(async ({ page }) => {
    expect(browserFailures.get(page) ?? [], 'unexpected browser runtime errors').toEqual([]);
});

async function data<T>(response: Awaited<ReturnType<APIRequestContext['post']>>) {
    expect(response.ok(), await response.text()).toBeTruthy();
    return (await response.json()).data as T;
}

async function authenticatePage(page: Page) {
    await page.addInitScript(({ authToken, authUserId }) => {
        localStorage.setItem('token', authToken);
        localStorage.setItem('finance-os:auth-user-id:v1', String(authUserId));
    }, { authToken: token, authUserId: userId });
}

async function openPosition(page: Page) {
    await page.goto('/investments');
    await expect(page.getByRole('heading', { name: '投资账本', exact: true })).toBeVisible();
    const row = page.getByRole('row').filter({ hasText: 'E2E 指数基金' }).first();
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: '详情' }).click();
    await expect(page.getByRole('heading', { name: '持仓详情' })).toBeVisible();
}

async function openTransaction(page: Page) {
    await page.goto('/investments');
    const row = page.getByRole('row').filter({ hasText: 'E2E 指数基金' }).last();
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: '详情' }).click();
    await expect(page.getByRole('heading', { name: '投资交易详情' })).toBeVisible();
}

async function openFirstBuy(page: Page) {
    await page.goto('/investments');
    await expect(page.getByRole('heading', { name: '投资账本', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '记录第一笔投资' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
}

async function selectFirstBuyBinding(page: Page, account: number, instrument: number) {
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('账户').selectOption(String(account));
    await dialog.getByLabel('投资标的').selectOption(String(instrument));
    await dialog.getByLabel('数量').fill('10');
    await dialog.getByLabel('单价').fill('100');
}

async function createPositionFixture(label: string, quantity = '10') {
    const headers = { Authorization: `Bearer ${token}` };
    const account = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: `${label} 账户`, type: 'BROKERAGE', currency: 'CNY' } }));
    const instrument = await data<{ id: number }>(await api.post('/api/v1/investment/instruments', { headers, data: { symbol: `E2E${runId.slice(-5)}F${++fixtureSequence}`, name: `${label} 标的`, market: 'CN', assetClass: 'ETF', quoteCurrency: 'CNY' } }));
    const firstBuy = await data<{ assetId: number; transactionId: number }>(await api.post('/api/v1/investment/positions', { headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() }, data: { accountId: account.id, instrumentId: instrument.id, quantity, unitPrice: '100', feeAmount: '1', taxAmount: '0' } }));
    return { accountId: account.id, instrumentId: instrument.id, assetId: firstBuy.assetId, transactionId: firstBuy.transactionId, instrumentName: `${label} 标的` };
}

async function openPositionByName(page: Page, instrumentName: string) {
    await page.goto('/investments');
    const row = page.getByRole('row').filter({ hasText: instrumentName }).first();
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: '详情' }).click();
    await expect(page.getByRole('heading', { name: '持仓详情' })).toBeVisible();
}

async function openClosedPositionByName(page: Page, instrumentName: string) {
    await page.goto('/investments');
    await page.getByLabel('持仓状态').selectOption('ALL');
    const row = page.getByRole('region', { name: '持仓' }).getByRole('row').filter({ hasText: instrumentName }).first();
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: '详情' }).click();
    await expect(page.getByRole('heading', { name: '持仓详情' })).toBeVisible();
}

const positionDetail = (page: Page) => page.locator('.investment-detail').filter({ has: page.getByRole('heading', { name: '持仓详情' }) });
const transactionDetail = (page: Page) => page.locator('.investment-detail').filter({ has: page.getByRole('heading', { name: '投资交易详情' }) });

async function openTransactionByName(page: Page, instrumentName: string, type: 'BUY' | 'SELL' | 'DIVIDEND') {
    await page.goto('/investments');
    const row = page.getByRole('row').filter({ hasText: instrumentName }).filter({ hasText: type === 'BUY' ? '买入' : type === 'SELL' ? '卖出' : '分红' }).first();
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: '详情' }).click();
    await expect(page.getByRole('heading', { name: '投资交易详情' })).toBeVisible();
}

function recordCommandRequests(page: Page, postPath: RegExp) {
    let postCount = 0;
    const getCounts = new Map<string, number>();
    let recording = false;
    page.on('request', request => {
        const path = new URL(request.url()).pathname;
        if (recording && request.method() === 'POST' && postPath.test(path)) postCount += 1;
        if (recording && request.method() === 'GET' && path.startsWith('/api/v1/investment/')) getCounts.set(path, (getCounts.get(path) ?? 0) + 1);
    });
    return {
        start: () => { recording = true; },
        expectStandardRefresh: () => {
            expect(postCount).toBe(1);
            expect(getCounts.get('/api/v1/investment/portfolio')).toBe(1);
            expect(getCounts.get('/api/v1/investment/positions')).toBe(1);
            expect(getCounts.get('/api/v1/investment/transactions')).toBe(1);
            expect([...getCounts.entries()].filter(([path]) => /^\/api\/v1\/investment\/positions\/\d+$/.test(path)).map(([, count]) => count)).toEqual([1]);
        },
        expectCorrectionRefresh: () => {
            expect(postCount).toBe(1);
            expect(getCounts.get('/api/v1/investment/portfolio')).toBe(1);
            expect(getCounts.get('/api/v1/investment/positions')).toBe(1);
            expect(getCounts.get('/api/v1/investment/transactions')).toBe(1);
            expect([...getCounts.entries()].filter(([path]) => /^\/api\/v1\/investment\/positions\/\d+$/.test(path)).map(([, count]) => count)).toEqual([1]);
            expect([...getCounts.entries()].filter(([path]) => /^\/api\/v1\/investment\/transactions\/\d+$/.test(path)).map(([, count]) => count)).toEqual([1]);
            expect([...getCounts.entries()].filter(([path]) => /^\/api\/v1\/investment\/transactions\/\d+\/audit-timeline$/.test(path)).map(([, count]) => count)).toEqual([1]);
        },
    };
}

test.beforeAll(async ({ baseURL }) => {
    api = await request.newContext({ baseURL });
    await data(await api.post('/api/v1/register', { data: { username, email: `${username}@example.test`, password } }));
    const login = await data<{ token: string; userId: number }>(await api.post('/api/v1/login', { data: { username, password } }));
    token = login.token; userId = login.userId;
    const headers = { Authorization: `Bearer ${token}` };
    const account = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: 'E2E 投资账户', type: 'BROKERAGE', currency: 'CNY' } }));
    accountId = account.id;
    const instrument = await data<{ id: number }>(await api.post('/api/v1/investment/instruments', { headers, data: { symbol: `E2E${runId.slice(-6)}`, name: 'E2E 指数基金', market: 'CN', assetClass: 'ETF', quoteCurrency: 'CNY' } }));
    instrumentId = instrument.id;
    const firstBuyAccount = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: 'E2E First BUY 账户', type: 'BROKERAGE', currency: 'CNY' } }));
    firstBuyAccountId = firstBuyAccount.id;
    const firstBuyInstrument = await data<{ id: number }>(await api.post('/api/v1/investment/instruments', { headers, data: { symbol: `E2EF${runId.slice(-6)}`, name: 'E2E First BUY 标的', market: 'CN', assetClass: 'ETF', quoteCurrency: 'CNY' } }));
    firstBuyInstrumentId = firstBuyInstrument.id;
    const conflictAccount = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: 'E2E First BUY 409 账户', type: 'BROKERAGE', currency: 'CNY' } }));
    firstBuyConflictAccountId = conflictAccount.id;
    const conflictInstrument = await data<{ id: number }>(await api.post('/api/v1/investment/instruments', { headers, data: { symbol: `E2EC${runId.slice(-6)}`, name: 'E2E First BUY 409 标的', market: 'CN', assetClass: 'ETF', quoteCurrency: 'CNY' } }));
    firstBuyConflictInstrumentId = conflictInstrument.id;
    firstBuyKey = crypto.randomUUID();
    const firstBuy = await data<{ assetId: number; transactionId: number }>(await api.post('/api/v1/investment/positions', { headers: { ...headers, 'Idempotency-Key': firstBuyKey }, data: { accountId, instrumentId, ...firstBuyBody } }));
    expect(firstBuy.assetId).toBeGreaterThan(0);
    expect(firstBuy.transactionId).toBeGreaterThan(0);
});

test.afterAll(async () => { await api?.dispose(); });

test('independent First BUY succeeds from an authoritative no-position binding with exact refresh reads', async ({ page }) => {
    await authenticatePage(page);
    await openFirstBuy(page);
    await selectFirstBuyBinding(page, firstBuyAccountId, firstBuyInstrumentId);
    await page.getByRole('button', { name: '进入复核' }).click();
    let postCount = 0;
    const getCounts = new Map<string, number>();
    let countRefresh = false;
    page.on('request', request => {
        const path = new URL(request.url()).pathname;
        if (request.method() === 'POST' && path === '/api/v1/investment/positions') postCount += 1;
        if (countRefresh && request.method() === 'GET' && path.startsWith('/api/v1/investment/')) getCounts.set(path, (getCounts.get(path) ?? 0) + 1);
    });
    countRefresh = true;
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/投资记录已提交/)).toBeVisible();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    expect(postCount).toBe(1);
    expect(getCounts.get('/api/v1/investment/portfolio')).toBe(1);
    expect(getCounts.get('/api/v1/investment/positions')).toBe(1);
    expect(getCounts.get('/api/v1/investment/transactions')).toBe(1);
    expect([...getCounts.entries()].filter(([path]) => /^\/api\/v1\/investment\/positions\/\d+$/.test(path)).map(([, count]) => count)).toEqual([1]);
});

test('independent First BUY 409 reconciles the exact binding reads without a duplicate write', async ({ page }) => {
    await authenticatePage(page);
    await openFirstBuy(page);
    await selectFirstBuyBinding(page, firstBuyConflictAccountId, firstBuyConflictInstrumentId);
    await page.getByRole('button', { name: '进入复核' }).click();
    let postCount = 0;
    const reconcileUrls: string[] = [];
    let countReconcile = false;
    page.on('request', request => {
        if (countReconcile && request.method() === 'GET' && new URL(request.url()).pathname.startsWith('/api/v1/investment/')) reconcileUrls.push(request.url());
    });
    await page.route('**/api/v1/investment/positions', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        postCount += 1;
        await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: 'conflict' }) });
    });
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByRole('button', { name: '重新核对当前账本' })).toBeVisible();
    countReconcile = true;
    await page.getByRole('button', { name: '重新核对当前账本' }).click();
    await expect(page.getByRole('button', { name: '重新核对当前账本' })).toHaveCount(0);
    expect(postCount).toBe(1);
    const paths = reconcileUrls.map(value => new URL(value));
    expect(paths.filter(url => url.pathname === '/api/v1/investment/portfolio')).toHaveLength(1);
    const positions = paths.filter(url => url.pathname === '/api/v1/investment/positions');
    expect(positions).toHaveLength(1);
    expect(positions[0].searchParams.get('status')).toBe('ALL');
    expect(positions[0].searchParams.get('accountId')).toBe(String(firstBuyConflictAccountId));
    expect(positions[0].searchParams.get('instrumentId')).toBe(String(firstBuyConflictInstrumentId));
    const transactions = paths.filter(url => url.pathname === '/api/v1/investment/transactions');
    expect(transactions).toHaveLength(1);
    expect(transactions[0].searchParams.get('accountId')).toBe(String(firstBuyConflictAccountId));
    expect(transactions[0].searchParams.get('instrumentId')).toBe(String(firstBuyConflictInstrumentId));
    expect(paths.filter(url => /^\/api\/v1\/investment\/positions\/\d+$/.test(url.pathname))).toHaveLength(0);
    expect(browserFailures.get(page)).toEqual(['console.error: Failed to load resource: the server responded with a status of 409 (Conflict)']);
    browserFailures.set(page, []);
});

test('SELL、reversal 与 replacement confirmation 显示冻结的业务复核内容', async ({ page }) => {
    await authenticatePage(page);
    await openPosition(page);
    await page.getByRole('button', { name: '记录卖出' }).click();
    await page.getByLabel('数量').fill('2');
    await page.getByLabel('单价').fill('101');
    await page.getByRole('button', { name: '进入复核' }).click();
    const sellDialog = page.getByRole('dialog');
    await expect(sellDialog.getByText('当前持仓数量')).toBeVisible();
    await expect(sellDialog.getByText('最终持仓、释放成本、已实现盈亏和账户余额由后端计算。')).toBeVisible();
    await page.getByRole('button', { name: '返回编辑' }).click();
    await page.getByRole('button', { name: '取消' }).click();

    await openTransaction(page);
    await page.getByRole('button', { name: '冲正交易' }).click();
    await page.getByLabel('纠正原因').fill('浏览器复核原始业务值');
    await page.getByRole('button', { name: '进入复核' }).click();
    const reversalDialog = page.getByRole('dialog');
    await expect(reversalDialog.getByText('原交易类型')).toBeVisible();
    await expect(reversalDialog.getByText('原数量')).toBeVisible();
    await expect(reversalDialog.getByText('生效交易时间')).toBeVisible();
    await expect(reversalDialog.getByText(/原记录不会删除/)).toBeVisible();
    await page.getByRole('button', { name: '返回编辑' }).click();
    await page.getByRole('button', { name: '取消' }).click();

    await page.getByRole('button', { name: '更正交易' }).click();
    await page.getByLabel('数量').fill('12');
    await page.getByLabel('单价').fill('101');
    await page.getByLabel('更正原因').fill('浏览器验证逐字段更正');
    await page.getByRole('button', { name: '进入复核' }).click();
    const comparison = page.getByRole('dialog').getByRole('table');
    await expect(comparison.getByRole('columnheader', { name: '原记录' })).toBeVisible();
    await expect(comparison.getByRole('columnheader', { name: '更正后' })).toBeVisible();
    const quantityComparison = comparison.locator('tbody tr').filter({ hasText: '数量' });
    await expect(quantityComparison).toContainText('10');
    await expect(quantityComparison).toContainText('12');
});

test('跨 Tab recovery 在请求 in-flight 时只发送一个 financial POST', async ({ browser }) => {
    const context: BrowserContext = await browser.newContext();
    const pageA = await context.newPage(); const pageB = await context.newPage();
    await authenticatePage(pageA); await authenticatePage(pageB);
    const storageKey = `finance-os:investment-command:pending:v1:${userId}:${firstBuyKey}`;
    const record = { schemaVersion: 1, userId, state: 'OUTCOME_UNKNOWN', commandType: 'FIRST_BUY', method: 'POST', path: '/investment/positions', idempotencyKey: firstBuyKey, bodyJson: JSON.stringify({ accountId, instrumentId, ...firstBuyBody }), target: { accountId, instrumentId }, submittedAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
    await pageA.goto('/login');
    await pageA.evaluate(({ key, value }) => localStorage.setItem(key, value), { key: storageKey, value: JSON.stringify(record) });
    let postCount = 0; let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    await context.route('**/api/v1/investment/positions', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        postCount += 1;
        await held;
        await route.continue();
    });
    await Promise.all([pageA.goto('/investments'), pageB.goto('/investments')]);
    await pageA.getByRole('button', { name: '恢复原提交' }).click();
    await expect(pageB.getByText(/RECOVERING/)).toBeVisible();
    await expect(pageB.getByRole('button', { name: '恢复原提交' })).toHaveCount(0);
    expect(postCount).toBe(1);
    release();
    await expect(pageA.getByText(/已恢复此前提交结果/)).toBeVisible();
    expect(postCount).toBe(1);
    await context.close();
});

test('显式 request subtype 与已冻结 BUY 证据矛盾时阻断 replacement recovery 且不发送 financial POST', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Replacement Recovery Contradiction');
    await authenticatePage(page);
    const idempotencyKey = crypto.randomUUID();
    const storageKey = `finance-os:investment-command:pending:v1:${userId}:${idempotencyKey}`;
    const record = {
        schemaVersion: 1, userId, state: 'OUTCOME_UNKNOWN', commandType: 'REPLACEMENT', method: 'POST',
        path: `/investment/transactions/${fixture.transactionId}/replacement`, idempotencyKey,
        bodyJson: JSON.stringify({ quantity: '11', unitPrice: '101', feeAmount: '1', taxAmount: '0', reason: '恢复证据校验' }),
        originalTransactionType: 'BUY', replacementRequestType: 'SELL', target: { assetId: fixture.assetId, logicalTransactionId: fixture.transactionId },
        submittedAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
    };
    let postCount = 0;
    page.on('request', request => {
        const path = new URL(request.url()).pathname;
        if (request.method() === 'POST' && /\/transactions\/\d+\/replacement$/.test(path)) postCount += 1;
    });
    await page.goto('/login');
    await page.evaluate(({ key, value }) => localStorage.setItem(key, value), { key: storageKey, value: JSON.stringify(record) });
    await page.goto('/investments');
    await expect(page.getByText(/RECOVERY_RECORD_INVALID/)).toBeVisible();
    await expect(page.getByRole('button', { name: '恢复原提交' })).toHaveCount(0);
    expect(postCount).toBe(0);
    await expect.poll(() => page.evaluate(key => JSON.parse(localStorage.getItem(key)!).state, storageKey)).toBe('OUTCOME_UNKNOWN');
});

test('write 成功但 required GET 失败时不声称当前数据已刷新', async ({ page }) => {
    await authenticatePage(page);
    await openPosition(page);
    let failNextPortfolio = false;
    await page.route('**/api/v1/investment/portfolio', async route => {
        if (failNextPortfolio) { failNextPortfolio = false; return route.abort('failed'); }
        await route.continue();
    });
    await page.getByRole('button', { name: '记录分红' }).click();
    await page.getByLabel('分红总额').fill('5');
    await page.getByRole('button', { name: '进入复核' }).click();
    failNextPortfolio = true;
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/投资记录已提交/)).toBeVisible();
    await expect(page.getByText(/当前账本数据尚未完成刷新/)).toBeVisible();
    await expect(page.getByText(/当前数据已从后端刷新/)).toHaveCount(0);
    const receipt = page.getByRole('status');
    await expect(receipt.getByText('交易类型', { exact: true })).toBeVisible();
    await expect(receipt.getByText('现金影响', { exact: true })).toBeVisible();
    await expect(receipt.getByText('账户余额（提交时）', { exact: true })).toBeVisible();
    await expect(receipt.getByText('提交时间', { exact: true })).toBeVisible();
    expect(browserFailures.get(page)).toEqual(['console.error: Failed to load resource: net::ERR_FAILED']);
    browserFailures.set(page, []); // Exact allowlist for this test's deliberate transport abort.
});

test('first BUY 409 reconciles the authoritative read model exactly once without a second POST', async ({ page }) => {
    await authenticatePage(page);
    await openPosition(page);
    const getCounts = new Map<string, number>();
    let reconcilePhase = false;
    let postCount = 0;
    page.on('request', request => {
        if (!reconcilePhase || request.method() !== 'GET') return;
        const path = new URL(request.url()).pathname;
        if (path.startsWith('/api/v1/investment/')) getCounts.set(path, (getCounts.get(path) ?? 0) + 1);
    });
    await page.route(`**/api/v1/investment/positions/*/buy`, async route => {
        if (route.request().method() !== 'POST') return route.continue();
        postCount += 1;
        await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: 'conflict' }) });
    });
    await page.getByRole('button', { name: '记录买入' }).click();
    await page.getByLabel('数量').fill('1');
    await page.getByLabel('单价').fill('100');
    await page.getByRole('button', { name: '进入复核' }).click();
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByRole('button', { name: '重新核对当前账本' })).toBeVisible();
    reconcilePhase = true;
    await page.getByRole('button', { name: '重新核对当前账本' }).click();
    await expect(page.getByRole('button', { name: '重新核对当前账本' })).toHaveCount(0);
    expect(postCount).toBe(1);
    expect(getCounts.get('/api/v1/investment/portfolio')).toBe(1);
    expect(getCounts.get('/api/v1/investment/positions')).toBe(1);
    expect(getCounts.get('/api/v1/investment/transactions')).toBe(1);
    expect([...getCounts.entries()].filter(([path]) => /^\/api\/v1\/investment\/positions\/\d+$/.test(path)).map(([, count]) => count)).toEqual([1]);
    expect(browserFailures.get(page)).toEqual(['console.error: Failed to load resource: the server responded with a status of 409 (Conflict)']);
    browserFailures.set(page, []); // Exact allowlist for this test's deliberate server conflict.
});

test('375px command dialog remains operable with labelled controls and keyboard focus', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await authenticatePage(page);
    await openPosition(page);
    await page.getByRole('button', { name: '记录卖出' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(page.getByLabel('数量')).toBeVisible();
    await expect(page.getByLabel('单价')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '进入复核' })).toBeVisible();
    expect(await page.locator('body').evaluate(element => element.scrollWidth <= window.innerWidth)).toBe(true);
    await expect(page.getByLabel('数量')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByLabel('单价')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(page.getByRole('button', { name: '记录卖出' })).toBeFocused();
});

test('Subsequent BUY 从权威持仓提交并刷新后端结果', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Subsequent BUY');
    await authenticatePage(page); await openPositionByName(page, fixture.instrumentName);
    await page.getByRole('button', { name: '记录买入' }).click();
    await page.getByLabel('数量').fill('2'); await page.getByLabel('单价').fill('120');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/positions\/\d+\/buy$/); requests.start();
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(positionDetail(page).getByText('12', { exact: true })).toBeVisible();
    requests.expectStandardRefresh();
});

test('Partial SELL 从权威持仓提交后保持 OPEN 并渲染后端数量', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Partial SELL');
    await authenticatePage(page); await openPositionByName(page, fixture.instrumentName);
    await page.getByRole('button', { name: '记录卖出' }).click();
    await page.getByLabel('数量').fill('3'); await page.getByLabel('单价').fill('110');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/positions\/\d+\/sell$/); requests.start();
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(positionDetail(page).getByText('持仓中', { exact: true })).toBeVisible();
    await expect(positionDetail(page).getByText('7', { exact: true })).toBeVisible();
    requests.expectStandardRefresh();
});

test('Full SELL 从权威持仓提交后进入 CLOSED', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Full SELL');
    await authenticatePage(page); await openPositionByName(page, fixture.instrumentName);
    await page.getByRole('button', { name: '记录卖出' }).click();
    await page.getByLabel('数量').fill('10'); await page.getByLabel('单价').fill('110');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/positions\/\d+\/sell$/); requests.start();
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(positionDetail(page).getByText('已关闭', { exact: true })).toBeVisible();
    requests.expectStandardRefresh();
});

test('CLOSED Position 的 BUY 重新打开持仓并渲染后端 OPEN 状态', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Reopen');
    const headers = { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() };
    await data(await api.post(`/api/v1/investment/positions/${fixture.assetId}/sell`, { headers, data: { quantity: '10', unitPrice: '110', feeAmount: '1', taxAmount: '0' } }));
    await authenticatePage(page); await openClosedPositionByName(page, fixture.instrumentName);
    await expect(positionDetail(page).getByText('已关闭', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '记录买入并重新打开' }).click();
    await page.getByLabel('数量').fill('2'); await page.getByLabel('单价').fill('130');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/positions\/\d+\/buy$/); requests.start();
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(positionDetail(page).getByText('持仓中', { exact: true })).toBeVisible();
    await expect(positionDetail(page).getByText('2', { exact: true })).toBeVisible();
    requests.expectStandardRefresh();
});

test('DIVIDEND 成功提交后保留权威持仓数量，并在校验失败时零 POST', async ({ page }) => {
    const fixture = await createPositionFixture('E2E DIVIDEND');
    await authenticatePage(page); await openPositionByName(page, fixture.instrumentName);
    await page.getByRole('button', { name: '记录分红' }).click();
    await page.getByLabel('分红总额').fill('5'); await page.getByLabel('费用').fill('3'); await page.getByLabel('税费').fill('3');
    let invalidPostCount = 0;
    page.on('request', request => { if (request.method() === 'POST' && /\/dividends$/.test(new URL(request.url()).pathname)) invalidPostCount += 1; });
    await page.getByRole('button', { name: '进入复核' }).click();
    await expect(page.getByText('费用与税费之和不能大于分红总额。')).toBeVisible();
    expect(invalidPostCount).toBe(0);
    await page.getByLabel('费用').fill('1'); await page.getByLabel('税费').fill('1');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/dividends$/); requests.start();
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(positionDetail(page).getByText('10', { exact: true })).toBeVisible();
    requests.expectStandardRefresh();
});

test('Standalone reversal 提交后渲染 REVERSED 与权威审计刷新', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Reversal');
    await authenticatePage(page); await openTransactionByName(page, fixture.instrumentName, 'BUY');
    await page.getByRole('button', { name: '冲正交易' }).click();
    await page.getByLabel('纠正原因').fill('浏览器完整提交冲正');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/transactions\/\d+\/reversal$/); requests.start();
    await page.getByRole('button', { name: '确认冲正' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(transactionDetail(page).getByText('已冲正', { exact: true })).toBeVisible();
    requests.expectCorrectionRefresh();
});

test('BUY replacement 同类型提交后渲染 REPLACED 和权威审计刷新', async ({ page }) => {
    const fixture = await createPositionFixture('E2E BUY Replacement');
    await authenticatePage(page); await openTransactionByName(page, fixture.instrumentName, 'BUY');
    await page.getByRole('button', { name: '更正交易' }).click();
    await page.getByLabel('数量').fill('12'); await page.getByLabel('单价').fill('101'); await page.getByLabel('更正原因').fill('浏览器 BUY 同类型更正');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/transactions\/\d+\/replacement$/); requests.start();
    await page.getByRole('button', { name: '确认更正' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(transactionDetail(page).getByText('已替换', { exact: true })).toBeVisible();
    requests.expectCorrectionRefresh();
});

test('SELL replacement 同类型提交后渲染 REPLACED 和权威审计刷新', async ({ page }) => {
    const fixture = await createPositionFixture('E2E SELL Replacement');
    const headers = { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() };
    await data(await api.post(`/api/v1/investment/positions/${fixture.assetId}/sell`, { headers, data: { quantity: '2', unitPrice: '110', feeAmount: '1', taxAmount: '0' } }));
    await authenticatePage(page); await openTransactionByName(page, fixture.instrumentName, 'SELL');
    await page.getByRole('button', { name: '更正交易' }).click();
    await page.getByLabel('数量').fill('2'); await page.getByLabel('单价').fill('111'); await page.getByLabel('更正原因').fill('浏览器 SELL 同类型更正');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/transactions\/\d+\/replacement$/); requests.start();
    await page.getByRole('button', { name: '确认更正' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(transactionDetail(page).getByText('已替换', { exact: true })).toBeVisible();
    requests.expectCorrectionRefresh();
});

test('DIVIDEND replacement 同类型提交后渲染 REPLACED 和权威审计刷新', async ({ page }) => {
    const fixture = await createPositionFixture('E2E DIVIDEND Replacement');
    const headers = { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() };
    await data(await api.post(`/api/v1/investment/positions/${fixture.assetId}/dividends`, { headers, data: { grossAmount: '5', feeAmount: '1', taxAmount: '1' } }));
    await authenticatePage(page); await openTransactionByName(page, fixture.instrumentName, 'DIVIDEND');
    await page.getByRole('button', { name: '更正交易' }).click();
    await page.getByLabel('分红总额').fill('6'); await page.getByLabel('费用').fill('1'); await page.getByLabel('税费').fill('1'); await page.getByLabel('更正原因').fill('浏览器 DIVIDEND 同类型更正');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/transactions\/\d+\/replacement$/); requests.start();
    await page.getByRole('button', { name: '确认更正' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(transactionDetail(page).getByText('已替换', { exact: true })).toBeVisible();
    requests.expectCorrectionRefresh();
});

test('First BUY 资格矩阵在浏览器中对非 ACTIVE 和不兼容账户 fail-closed', async ({ page }) => {
    const headers = { Authorization: `Bearer ${token}` };
    const inactive = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: 'E2E 非活动账户', type: 'BROKERAGE', currency: 'CNY' } }));
    await data(await api.post(`/api/v1/accounts/${inactive.id}/deactivate`, { headers }));
    const incompatible = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: 'E2E 不兼容账户', type: 'CASH', currency: 'CNY' } }));
    const instrument = await data<{ id: number }>(await api.post('/api/v1/investment/instruments', { headers, data: { symbol: `E2EEL${runId.slice(-5)}`, name: 'E2E 资格标的', market: 'CN', assetClass: 'ETF', quoteCurrency: 'CNY' } }));
    await authenticatePage(page); await openFirstBuy(page);
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('账户').selectOption(String(inactive.id)); await dialog.getByLabel('投资标的').selectOption(String(instrument.id));
    await expect(page.getByText('账户或标的当前不可用于记录买入。')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '进入复核' })).toBeDisabled();
    await dialog.getByLabel('账户').selectOption(String(incompatible.id));
    await expect(dialog.getByRole('button', { name: '进入复核' })).toBeDisabled();
    await dialog.getByLabel('账户').selectOption(String(firstBuyAccountId));
    await dialog.getByLabel('投资标的').selectOption(String(firstBuyInstrumentId));
    await expect(dialog.getByRole('button', { name: '进入复核' })).toBeEnabled();
});

test('401 首次 POST 保留 AUTH_REQUIRED/SUBMITTING 且不自动重放', async ({ page }) => {
    await authenticatePage(page); await openFirstBuy(page); await selectFirstBuyBinding(page, firstBuyConflictAccountId, firstBuyConflictInstrumentId);
    await page.getByRole('button', { name: '进入复核' }).click();
    let postCount = 0;
    await page.route('**/api/v1/investment/positions', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        postCount += 1; await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'unauthorized' }) });
    });
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const records = await page.evaluate(() => Object.keys(localStorage)
        .filter(key => key.startsWith('finance-os:investment-command:pending:v1:'))
        .map(key => JSON.parse(localStorage.getItem(key)!))
        .filter((value: { state?: string }) => value.state === 'AUTH_REQUIRED')) as Array<{ resumeState?: string }>;
    expect(records).toHaveLength(1); expect(records[0].resumeState).toBe('SUBMITTING'); expect(postCount).toBe(1);
    expect(browserFailures.get(page)).toEqual(['console.error: Failed to load resource: the server responded with a status of 401 (Unauthorized)']); browserFailures.set(page, []);
});

test('replacement 首次 POST 401 后重认证时权威 subtype 矛盾会阻断恢复写入', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Replacement 401 Evidence Gate');
    await authenticatePage(page); await openTransactionByName(page, fixture.instrumentName, 'BUY');
    await page.getByRole('button', { name: '更正交易' }).click();
    await page.getByLabel('数量').fill('11'); await page.getByLabel('单价').fill('101'); await page.getByLabel('更正原因').fill('401 后权威类型变化');
    await page.getByRole('button', { name: '进入复核' }).click();
    let initialPosts = 0; let resumedPosts = 0; let afterAuthentication = false;
    await page.route('**/api/v1/investment/transactions/*/replacement', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        if (afterAuthentication) { resumedPosts += 1; return route.continue(); }
        initialPosts += 1;
        await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'unauthorized' }) });
    });
    await page.route(`**/api/v1/investment/transactions/${fixture.transactionId}`, async route => {
        if (!afterAuthentication || route.request().method() !== 'GET') return route.continue();
        const response = await route.fetch();
        const payload = await response.json() as { data: Record<string, unknown> };
        await route.fulfill({ response, body: JSON.stringify({ ...payload, data: { ...payload.data, transactionType: 'SELL' } }) });
    });
    await page.getByRole('button', { name: '确认更正' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(initialPosts).toBe(1);
    await page.evaluate(({ authToken, authUserId }) => { localStorage.setItem('token', authToken); localStorage.setItem('finance-os:auth-user-id:v1', String(authUserId)); }, { authToken: token, authUserId: userId });
    afterAuthentication = true;
    await page.goto('/investments');
    await expect(page.getByRole('button', { name: '恢复原提交' })).toBeVisible();
    await page.getByRole('button', { name: '恢复原提交' }).click();
    await page.waitForTimeout(200);
    expect(initialPosts).toBe(1);
    expect(resumedPosts).toBe(0);
    const records = await page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('finance-os:investment-command:pending:v1:')).map(key => JSON.parse(localStorage.getItem(key)!))) as Array<{ state: string; resumeState?: string }>;
    expect(records).toHaveLength(1); expect(records[0]).toMatchObject({ state: 'AUTH_REQUIRED', resumeState: 'SUBMITTING' });
    expect(browserFailures.get(page)).toEqual(['console.error: Failed to load resource: the server responded with a status of 401 (Unauthorized)']); browserFailures.set(page, []);
});

test('replacement 首次 POST 401 后重认证且三方证据一致时仅恢复一次写入', async ({ page }) => {
    const fixture = await createPositionFixture('E2E Replacement 401 Valid Resume');
    await authenticatePage(page); await openTransactionByName(page, fixture.instrumentName, 'BUY');
    await page.getByRole('button', { name: '更正交易' }).click();
    await page.getByLabel('数量').fill('11'); await page.getByLabel('单价').fill('101'); await page.getByLabel('更正原因').fill('401 后合法恢复');
    await page.getByRole('button', { name: '进入复核' }).click();
    let initialPosts = 0; let resumedPosts = 0; let afterAuthentication = false;
    await page.route('**/api/v1/investment/transactions/*/replacement', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        if (afterAuthentication) { resumedPosts += 1; return route.continue(); }
        initialPosts += 1;
        await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'unauthorized' }) });
    });
    await page.getByRole('button', { name: '确认更正' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(initialPosts).toBe(1);
    await page.evaluate(({ authToken, authUserId }) => { localStorage.setItem('token', authToken); localStorage.setItem('finance-os:auth-user-id:v1', String(authUserId)); }, { authToken: token, authUserId: userId });
    afterAuthentication = true;
    await page.goto('/investments');
    await page.getByRole('button', { name: '恢复原提交' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    expect(initialPosts).toBe(1);
    expect(resumedPosts).toBe(1);
    expect(browserFailures.get(page)).toEqual(['console.error: Failed to load resource: the server responded with a status of 401 (Unauthorized)']); browserFailures.set(page, []);
});

test('CLOSED Position 允许记录分红，提交一次且不会重新打开持仓', async ({ page }) => {
    const fixture = await createPositionFixture('E2E CLOSED DIVIDEND');
    const headers = { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() };
    await data(await api.post(`/api/v1/investment/positions/${fixture.assetId}/sell`, { headers, data: { quantity: '10', unitPrice: '110', feeAmount: '1', taxAmount: '0' } }));
    await authenticatePage(page); await openClosedPositionByName(page, fixture.instrumentName);
    await expect(positionDetail(page).getByText('已关闭', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '记录分红' })).toBeEnabled();
    await page.getByRole('button', { name: '记录分红' }).click();
    await page.getByLabel('分红总额').fill('5');
    await page.getByRole('button', { name: '进入复核' }).click();
    const requests = recordCommandRequests(page, /\/dividends$/); requests.start();
    await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(positionDetail(page).getByText('已关闭', { exact: true })).toBeVisible();
    requests.expectStandardRefresh();
});

test('401 recovery POST 保留 OUTCOME_UNKNOWN 来源且不会自动重放', async ({ page }) => {
    await authenticatePage(page); await openPosition(page);
    let postCount = 0; let mode: 'network' | 'unauthorized' = 'network';
    await page.route('**/api/v1/investment/positions/*/buy', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        postCount += 1;
        if (mode === 'network') return route.abort('failed');
        return route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'unauthorized' }) });
    });
    await page.getByRole('button', { name: '记录买入' }).click();
    await page.getByLabel('数量').fill('1'); await page.getByLabel('单价').fill('100');
    await page.getByRole('button', { name: '进入复核' }).click(); await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByRole('button', { name: '恢复原提交' })).toBeVisible();
    mode = 'unauthorized';
    await page.getByRole('button', { name: '恢复原提交' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const records = await page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('finance-os:investment-command:pending:v1:')).map(key => JSON.parse(localStorage.getItem(key)!))) as Array<{ state: string; resumeState?: string }>;
    expect(records).toHaveLength(1); expect(records[0]).toMatchObject({ state: 'AUTH_REQUIRED', resumeState: 'OUTCOME_UNKNOWN' }); expect(postCount).toBe(2);
    await page.waitForTimeout(200); expect(postCount).toBe(2);
    expect(browserFailures.get(page)).toEqual([
        'console.error: Failed to load resource: net::ERR_FAILED',
        'console.error: Failed to load resource: the server responded with a status of 401 (Unauthorized)',
    ]); browserFailures.set(page, []);
});

test('401 success-refresh GET 保留成功回执并在重新认证后只继续 GET', async ({ page }) => {
    await authenticatePage(page); await openPosition(page);
    let postCount = 0; let refreshGetCount = 0; let failRefresh = false; let recoveryRefresh = false;
    page.on('request', request => { const path = new URL(request.url()).pathname; if (request.method() === 'POST' && /\/buy$/.test(path)) postCount += 1; if ((failRefresh || recoveryRefresh) && request.method() === 'GET' && path.startsWith('/api/v1/investment/')) refreshGetCount += 1; });
    await page.route('**/api/v1/investment/portfolio', async route => failRefresh ? route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'unauthorized' }) }) : route.continue());
    await page.getByRole('button', { name: '记录买入' }).click();
    await page.getByLabel('数量').fill('1'); await page.getByLabel('单价').fill('100');
    await page.getByRole('button', { name: '进入复核' }).click();
    failRefresh = true; await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const beforeRecovery = await page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('finance-os:investment-command:pending:v1:')).map(key => JSON.parse(localStorage.getItem(key)!))) as Array<{ state: string; resumeState?: string }>;
    expect(beforeRecovery).toHaveLength(1); expect(beforeRecovery[0]).toMatchObject({ state: 'AUTH_REQUIRED', resumeState: 'SUCCEEDED_AWAITING_REFRESH' }); expect(postCount).toBe(1); expect(refreshGetCount).toBe(4);
    await page.evaluate(({ authToken, authUserId }) => { localStorage.setItem('token', authToken); localStorage.setItem('finance-os:auth-user-id:v1', String(authUserId)); }, { authToken: token, authUserId: userId });
    failRefresh = false; await page.goto('/investments');
    await expect(page.getByText(/写入已成功，当前数据尚未刷新/)).toBeVisible(); recoveryRefresh = true; await page.getByRole('button', { name: '重新刷新' }).click();
    await expect(page.getByRole('button', { name: '重新刷新' })).toHaveCount(0); expect(postCount).toBe(1); expect(refreshGetCount).toBe(8);
    expect(browserFailures.get(page)).toEqual(['console.error: Failed to load resource: the server responded with a status of 401 (Unauthorized)']); browserFailures.set(page, []);
});

test('401 reconcile GET 保留 409 marker 且重新认证后只继续 GET', async ({ page }) => {
    await authenticatePage(page); await openPosition(page);
    let postCount = 0; let reconcileGetCount = 0; let failReconcile = false;
    await page.route('**/api/v1/investment/positions/*/buy', async route => {
        if (route.request().method() !== 'POST') return route.continue();
        postCount += 1; return route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: 'conflict' }) });
    });
    let reconcilePhase = false;
    page.on('request', request => { if (reconcilePhase && request.method() === 'GET' && new URL(request.url()).pathname === '/api/v1/investment/portfolio') reconcileGetCount += 1; });
    await page.route('**/api/v1/investment/portfolio', async route => failReconcile ? route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: 'unauthorized' }) }) : route.continue());
    await page.getByRole('button', { name: '记录买入' }).click();
    await page.getByLabel('数量').fill('1'); await page.getByLabel('单价').fill('100');
    await page.getByRole('button', { name: '进入复核' }).click(); await page.getByRole('button', { name: '确认记录' }).click();
    await expect(page.getByRole('button', { name: '重新核对当前账本' })).toBeVisible();
    reconcilePhase = true; failReconcile = true; await page.getByRole('button', { name: '重新核对当前账本' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const beforeRecovery = await page.evaluate(() => Object.keys(localStorage).filter(key => key.startsWith('finance-os:investment-command:pending:v1:')).map(key => JSON.parse(localStorage.getItem(key)!))) as Array<{ state: string; resumeState?: string }>;
    expect(beforeRecovery).toHaveLength(1); expect(beforeRecovery[0]).toMatchObject({ state: 'AUTH_REQUIRED', resumeState: 'REJECTED_AWAITING_RECONCILE' }); expect(postCount).toBe(1); expect(reconcileGetCount).toBe(1);
    await page.evaluate(({ authToken, authUserId }) => { localStorage.setItem('token', authToken); localStorage.setItem('finance-os:auth-user-id:v1', String(authUserId)); }, { authToken: token, authUserId: userId });
    failReconcile = false; reconcilePhase = false; await page.goto('/investments');
    reconcilePhase = true; await page.getByRole('button', { name: '重新核对当前账本' }).click();
    await expect(page.getByRole('button', { name: '重新核对当前账本' })).toHaveCount(0); expect(postCount).toBe(1); expect(reconcileGetCount).toBe(4);
    expect(browserFailures.get(page)).toEqual([
        'console.error: Failed to load resource: the server responded with a status of 409 (Conflict)',
        'console.error: Failed to load resource: the server responded with a status of 401 (Unauthorized)',
    ]); browserFailures.set(page, []);
});

test('First BUY 对不兼容、缺失和未知权威标的资格 fail-closed，且零 POST', async ({ page }) => {
    const headers = { Authorization: `Bearer ${token}` };
    const account = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: 'E2E 权威资格账户', type: 'BROKERAGE', currency: 'CNY' } }));
    const incompatible = await data<{ id: number }>(await api.post('/api/v1/investment/instruments', { headers, data: { symbol: `E2ECI${runId.slice(-5)}`, name: 'E2E 不兼容标的', market: 'CN', assetClass: 'CRYPTO', quoteCurrency: 'CNY' } }));
    const compatible = await data<{ id: number }>(await api.post('/api/v1/investment/instruments', { headers, data: { symbol: `E2ECM${runId.slice(-5)}`, name: 'E2E 缺失未知标的', market: 'CN', assetClass: 'ETF', quoteCurrency: 'CNY' } }));
    let instrumentMode: 'actual' | 'missing' | 'unknown' = 'actual'; let postCount = 0;
    await page.route('**/api/v1/investment/instruments', async route => {
        if (route.request().method() !== 'GET') return route.continue();
        const response = await route.fetch(); const payload = await response.json() as { data: Array<Record<string, unknown>> };
        const data = payload.data.map(item => item.id === compatible.id && instrumentMode === 'missing' ? (() => { const copy = { ...item }; delete copy.status; return copy; })() : item.id === compatible.id && instrumentMode === 'unknown' ? { ...item, status: 'UNKNOWN' } : item);
        await route.fulfill({ response, body: JSON.stringify({ ...payload, data }) });
    });
    page.on('request', request => { if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/v1/investment/positions') postCount += 1; });
    await authenticatePage(page); await openFirstBuy(page);
    const dialog = page.getByRole('dialog'); await dialog.getByLabel('账户').selectOption(String(account.id)); await dialog.getByLabel('投资标的').selectOption(String(incompatible.id));
    await expect(dialog.getByRole('button', { name: '进入复核' })).toBeDisabled(); await expect(page.getByText('账户或标的当前不可用于记录买入。')).toBeVisible();
    instrumentMode = 'missing'; await page.reload(); await page.getByRole('button', { name: '记录第一笔投资' }).click();
    await page.getByRole('dialog').getByLabel('账户').selectOption(String(account.id)); await page.getByRole('dialog').getByLabel('投资标的').selectOption(String(compatible.id));
    await expect(page.getByRole('dialog').getByRole('button', { name: '进入复核' })).toBeDisabled();
    instrumentMode = 'unknown'; await page.reload(); await page.getByRole('button', { name: '记录第一笔投资' }).click();
    await page.getByRole('dialog').getByLabel('账户').selectOption(String(account.id)); await page.getByRole('dialog').getByLabel('投资标的').selectOption(String(compatible.id));
    await expect(page.getByRole('dialog').getByRole('button', { name: '进入复核' })).toBeDisabled(); expect(postCount).toBe(0);
});

test('冻结的对话框无障碍语义关联标签、校验错误、键盘取消和焦点返回', async ({ page }) => {
    await authenticatePage(page); await openPosition(page);
    const trigger = page.getByRole('button', { name: '记录分红' }); await trigger.click();
    const dialog = page.getByRole('dialog'); await expect(dialog).toHaveAttribute('aria-modal', 'true'); await expect(dialog).toHaveAccessibleName('记录分红');
    const amount = page.getByLabel('分红总额'); await expect(amount).toBeFocused(); await amount.fill('0'); await page.getByRole('button', { name: '进入复核' }).click();
    const error = page.getByRole('alert').filter({ hasText: '分红总额必须大于 0。' }); await expect(error).toBeVisible();
    expect((await amount.getAttribute('aria-describedby'))?.split(' ')).toContain(await error.getAttribute('id'));
    await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0); await expect(trigger).toBeFocused();
});
