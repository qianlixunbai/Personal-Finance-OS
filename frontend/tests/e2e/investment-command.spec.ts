import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';

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
    await openTransactionByName(page, 'E2E First BUY 标的', 'BUY');
    await page.getByRole('button', { name: '更正交易' }).click();
    await page.getByLabel('数量').fill('12'); await page.getByLabel('单价').fill('101'); await page.getByLabel('更正原因').fill('浏览器 First BUY 后同类型更正');
    await page.getByRole('button', { name: '进入复核' }).click();
    const correctionRequests = recordCommandRequests(page, /\/transactions\/\d+\/replacement$/); correctionRequests.start();
    await page.getByRole('button', { name: '确认更正' }).click();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    await expect(transactionDetail(page).getByText('已替换', { exact: true })).toBeVisible();
    correctionRequests.expectCorrectionRefresh();
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
