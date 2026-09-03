import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';

const runId = `${Date.now()}-${Math.floor(Math.random() * 10_000)}`;
const username = `import_e2e_${runId}`;
const password = 'Import-E2E-2026!';
const fileName = `import-e2e-${runId}.csv`;
const accountName = `导入 E2E 账户 ${runId}`;
const categoryName = `导入 E2E 分类 ${runId}`;
let api: APIRequestContext;
let token: string;
let userId: number;
let accountId: number;
let categoryId: number;

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

test.beforeAll(async ({ baseURL }) => {
    api = await request.newContext({ baseURL });
    await data(await api.post('/api/v1/register', { data: { username, email: `${username}@example.test`, password } }));
    const login = await data<{ token: string; userId: number }>(await api.post('/api/v1/login', { data: { username, password } }));
    token = login.token;
    userId = login.userId;
    const headers = { Authorization: `Bearer ${token}` };
    const account = await data<{ id: number }>(await api.post('/api/v1/accounts', { headers, data: { name: accountName, type: 'CASH', currency: 'CNY' } }));
    accountId = account.id;
    const category = await data<{ id: number }>(await api.post('/api/v1/categories', { headers, data: { name: categoryName, type: 'EXPENSE', parentId: null, sortOrder: 0 } }));
    categoryId = category.id;
});

test.afterAll(async () => { await api?.dispose(); });

test('真实后端导入临界旅程经由 Vite 代理创建权威回执', async ({ page, baseURL }) => {
    const importRequests: Array<{ method: string; url: string; idempotencyKey?: string }> = [];
    page.on('request', request => {
        const url = new URL(request.url());
        if (url.pathname.startsWith('/api/v1/imports/transactions')) {
            importRequests.push({ method: request.method(), url: request.url(), idempotencyKey: request.headers()['idempotency-key'] });
        }
    });

    await authenticatePage(page);
    await page.goto('/transactions/import');
    await page.getByLabel('选择 CSV 或 XLSX 文件').setInputFiles({
        name: fileName,
        mimeType: 'text/csv',
        buffer: Buffer.from(`日期,类型,金额,账户,分类,说明\n2026-09-01,支出,10.00,现金,餐饮,真实导入 ${runId}\n2026-09-01,支出,10.00,现金,餐饮,真实导入 ${runId}`),
    });
    await page.getByRole('button', { name: '上传并开始映射' }).click();
    await expect(page.getByRole('heading', { name: '映射字段' })).toBeVisible();

    for (const [target, source] of [['日期', '日期'], ['类型', '类型'], ['金额', '金额'], ['账户', '账户'], ['分类', '分类'], ['说明', '说明']] as const) {
        await page.getByLabel(`${target}来源列`).selectOption(source);
    }
    await page.getByRole('button', { name: '保存列映射并发现来源值' }).click();
    await page.getByLabel('类型 支出').selectOption('EXPENSE');
    await page.getByLabel('账户 现金').selectOption(String(accountId));
    await page.getByLabel('分类 餐饮').selectOption(String(categoryId));
    await page.getByRole('button', { name: '提交正式映射并生成预览' }).click();

    await expect(page.getByRole('heading', { name: '服务端预览' })).toBeVisible();
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    await page.getByRole('checkbox').check();
    await expect(page.getByText('Ready to confirm')).toBeVisible();
    await page.getByRole('button', { name: '打开最终确认' }).click();
    await page.getByRole('button', { name: /确认导入 2 条流水/ }).click();

    await expect(page.getByRole('heading', { name: '权威导入回执' })).toBeVisible();
    await expect(page.getByText(fileName)).toBeVisible();
    const receiptSummary = page.locator('.import-preview-summary').first();
    await expect(receiptSummary).toContainText('总行数2');
    await expect(receiptSummary).toContainText('已创建2');
    expect(importRequests.some(request => request.method === 'POST' && request.url === `${baseURL}/api/v1/imports/transactions/preview`)).toBeTruthy();
    const confirm = importRequests.find(request => request.method === 'POST' && /\/confirm$/.test(new URL(request.url).pathname));
    expect(confirm?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i);
    expect(importRequests.some(request => request.method === 'GET' && /\/batches\//.test(new URL(request.url).pathname))).toBeTruthy();
    expect(importRequests.every(request => new URL(request.url).port === new URL(baseURL!).port)).toBeTruthy();
});
