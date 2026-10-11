import { expect, test, type BrowserContext, type Page } from '@playwright/test';

// All identities, credentials and answers in this suite are fictional fixtures.
const userA = { username: 'c6-user-a', userId: 71 };
const userB = { username: 'c6-user-b', userId: 72 };
const aiPath = '**/api/v1/ai/ask';

test.beforeAll(async ({ browser, browserName }) => {
    console.log(`C6 实跑浏览器：${browserName} ${browser.version()}`);
});

function deferred() {
    let resolve!: () => void;
    const promise = new Promise<void>(done => { resolve = done; });
    return { promise, resolve };
}

async function mockAuthAndReads(context: BrowserContext, repeatToken = false) {
    let loginSequence = 0;
    await context.route('**/api/v1/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/v1/ai/ask') return route.fallback();
        if (path === '/api/v1/login') {
            const body = route.request().postDataJSON() as { username: string };
            const user = body.username === userA.username ? userA : userB;
            await route.fulfill({ json: { code: 200, data: {
                token: repeatToken ? `c6-fictional-user-${user.userId}` : `c6-fictional-session-${++loginSequence}`, userId: user.userId,
            } } });
            return;
        }
        // The test enters AI immediately after Login's existing investment redirect.
        const data = path.endsWith('/portfolio') ? null
            : path.endsWith('/positions') || path.endsWith('/transactions')
                ? { records: [], nextCursor: null, hasMore: false, size: 20 } : [];
        await route.fulfill({ json: { code: 200, data } });
    });
}

async function login(page: Page, user: typeof userA) {
    await page.goto('/login');
    await page.getByRole('textbox', { name: '用户名' }).fill(user.username);
    await page.getByLabel('密码', { exact: true }).fill('fictional-c6-password');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page).toHaveURL(/\/investments$/);
    await page.goto('/ai');
    await expect(page.getByRole('heading', { name: 'AI 财务分析', exact: true })).toBeVisible();
}

async function switchUser(page: Page, user: typeof userA) {
    await page.getByRole('button', { name: '退出登录', exact: true }).first().click();
    await expect(page).toHaveURL(/\/login$/);
    await login(page, user);
}

async function submit(page: Page, question: string) {
    await page.getByRole('textbox', { name: '问题', exact: true }).fill(question);
    await page.getByRole('button', { name: '提交问题', exact: true }).click();
}

async function openTwoTabs(context: BrowserContext, page: Page, repeatToken = false) {
    await mockAuthAndReads(context, repeatToken);
    await login(page, userA);
    const otherTab = await context.newPage();
    await otherTab.goto('/ai');
    await expect(otherTab.getByRole('heading', { name: 'AI 财务分析', exact: true })).toBeVisible();
    return otherTab;
}

async function expectEmpty(page: Page) {
    await expect(page.locator('.ai-analyst__answer')).toHaveCount(0);
    await expect(page.locator('.ai-analyst__question')).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: '问题', exact: true })).toHaveValue('');
    await expect(page.getByRole('status')).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
}

async function auth(page: Page) {
    return page.evaluate(() => ({ token: localStorage.getItem('token'),
        userId: localStorage.getItem('finance-os:auth-user-id:v1'),
        session: localStorage.getItem('finance-os:auth-session:v1') }));
}

// Observe the real Axios response chain, then drain its Promise continuations
// and a React paint. No fixed sleep is used to declare a negative result.
async function observeResponses(page: Page) {
    await page.evaluate(async () => {
        const path = '/src/api/index.ts';
        const { default: api } = await import(path);
        const observed = window as Window & { c6Settled: number };
        observed.c6Settled = 0;
        api.interceptors.response.use((response: { config: { url: string } }) => {
            if (response.config.url === '/ai/ask') observed.c6Settled++;
            return response;
        }, (error: { config?: { url: string } }) => {
            if (error.config?.url === '/ai/ask') observed.c6Settled++;
            return Promise.reject(error);
        });
    });
}

async function waitConsumed(page: Page, count = 1) {
    await page.waitForFunction(expected => (window as Window & { c6Settled: number }).c6Settled >= expected, count);
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
}

test('C6 completed answer and draft disappear after real second-tab logout and B login', async ({ context, page }, testInfo) => {
    await context.route(aiPath, route => route.fulfill({ json: { code: 200, data: { answer: 'C6 A 专属虚构回答' } } }));
    const otherTab = await openTwoTabs(context, page);
    await submit(page, 'C6 A 专属问题');
    await expect(page.locator('.ai-analyst__answer')).toHaveText('C6 A 专属虚构回答');
    await page.getByRole('textbox', { name: '问题', exact: true }).fill('C6 A 未提交草稿');
    await switchUser(otherTab, userB);
    await page.screenshot({ path: testInfo.outputPath('completed-after-switch.png') });
    await expect(page.locator('.ai-analyst__answer')).toHaveCount(0);
    await expect(page.locator('.ai-analyst__question')).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: '问题', exact: true })).toHaveValue('');
    await expect(page.getByRole('status')).toHaveCount(0);
    await expect(page.getByRole('alert')).toHaveCount(0);
});

for (const status of [200, 503, 401]) {
    test(`C6 pending A ${status} cannot alter B auth or UI after second-tab login`, async ({ context, page }, testInfo) => {
        const arrived = deferred();
        const released = deferred();
        const delivered = deferred();
        const bArrived = deferred();
        const bReleased = deferred();
        let requests = 0;
        const authorizations: Array<string | undefined> = [];
        await context.route(aiPath, async route => {
            requests++;
            authorizations.push(route.request().headers().authorization);
            if (route.request().postDataJSON().question !== 'C6 A pending') {
                bArrived.resolve();
                await bReleased.promise;
                await route.fulfill({ json: { code: 200, data: { answer: 'C6 B 新会话回答' } } });
                return;
            }
            arrived.resolve();
            await released.promise;
            await route.fulfill({ status, json: status === 200
                ? { code: 200, data: { answer: 'C6 A 迟到虚构回答' } }
                : { code: status, message: 'C6 A 旧请求错误' } });
            delivered.resolve();
        });
        const otherTab = await openTwoTabs(context, page);
        await observeResponses(page);
        const aAuth = await auth(page);
        await submit(page, 'C6 A pending');
        await arrived.promise;
        await switchUser(otherTab, userB);
        const bAuth = await auth(otherTab);
        await expectEmpty(page);
        await submit(page, 'C6 B 新问题');
        await bArrived.promise;
        expect(authorizations).toEqual([`Bearer ${aAuth.token}`, `Bearer ${bAuth.token}`]);
        const oldResponse = page.waitForResponse(response => response.url().endsWith('/api/v1/ai/ask'));
        released.resolve();
        await delivered.promise;
        await oldResponse;
        await waitConsumed(page);
        await page.screenshot({ path: testInfo.outputPath(`pending-${status}-after-switch.png`) });
        expect(await auth(otherTab)).toEqual(bAuth);
        await expect(page).toHaveURL(/\/ai$/);
        await expect(page.locator('.ai-analyst__answer')).toHaveCount(0);
        await expect(page.getByRole('alert')).toHaveCount(0);
        await expect(page.getByRole('status')).toHaveText('正在分析…');
        await expect(page.getByRole('textbox', { name: '问题', exact: true })).toHaveValue('C6 B 新问题');
        await expect(page.getByRole('textbox', { name: '问题', exact: true })).toBeDisabled();
        await page.locator('form.ai-analyst__composer').evaluate(form => (form as HTMLFormElement).requestSubmit());
        await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => resolve())));
        expect(requests).toBe(2); // Old finally must not release B's pending lock.
        bReleased.resolve();
        await expect(page.locator('.ai-analyst__answer')).toHaveText('C6 B 新会话回答');
        await expect(page.getByRole('alert')).toHaveCount(0);
        await expect(page.getByRole('status')).toHaveCount(0);
        expect(await auth(otherTab)).toEqual(bAuth);
    });
}

for (const transition of ['same-user', 'ABA']) {
    for (const status of [200, 503, 401]) {
        test(`C6 ${transition} with identical A token rejects old ${status}`, async ({ context, page }, testInfo) => {
            const arrived = deferred();
            const released = deferred();
            await context.route(aiPath, async route => {
                if (route.request().postDataJSON().question === '新 A 问题') {
                    return route.fulfill({ json: { code: 200, data: { answer: '新 A 回答' } } });
                }
                arrived.resolve();
                await released.promise;
                await route.fulfill({ status, json: status === 200
                    ? { code: 200, data: { answer: '旧 A 回答' } }
                    : { code: status, message: '旧 A 错误' } });
            });
            const otherTab = await openTwoTabs(context, page, true);
            await observeResponses(page);
            const oldAuth = await auth(page);
            await submit(page, '旧 A 问题');
            await arrived.promise;
            if (transition === 'ABA') await switchUser(otherTab, userB);
            await otherTab.getByRole('button', { name: '退出登录', exact: true }).first().click();
            await expect(otherTab).toHaveURL(/\/login$/);
            await expectEmpty(page);
            expect(await auth(otherTab)).toEqual({ token: null, userId: null, session: null });
            await login(otherTab, userA);
            const newAuth = await auth(otherTab);
            expect(newAuth.token).toBe(oldAuth.token);
            expect(newAuth.userId).toBe(oldAuth.userId);
            expect(newAuth.session).not.toBe(oldAuth.session);
            await expectEmpty(page);
            released.resolve();
            await waitConsumed(page);
            await expectEmpty(page);
            expect(await auth(otherTab)).toEqual(newAuth);
            await expect(page).toHaveURL(/\/ai$/);
            await submit(page, '新 A 问题');
            await expect(page.locator('.ai-analyst__answer')).toHaveText('新 A 回答');
            await page.screenshot({ path: testInfo.outputPath(`${transition}-${status}.png`) });
        });
    }
}

test('C6 current nonce session 401 clears only auth and redirects', async ({ context, page }) => {
    await mockAuthAndReads(context);
    await login(page, userA);
    expect((await auth(page)).session).toBeTruthy();
    await page.evaluate(() => localStorage.setItem('c6-unrelated-record', 'preserve'));
    await context.route(aiPath, route => route.fulfill({ status: 401, json: { code: 401, message: '登录失效' } }));
    await submit(page, '当前有效会话 401');
    await expect(page).toHaveURL(/\/login$/);
    expect(await auth(page)).toEqual({ token: null, userId: null, session: null });
    expect(await page.evaluate(() => localStorage.getItem('c6-unrelated-record'))).toBe('preserve');
});

test('C6 send interceptor refuses an old question before a new token can be attached', async ({ context, page }) => {
    await mockAuthAndReads(context);
    await login(page, userA);
    let requests = 0;
    await context.route(aiPath, route => { requests++; return route.fulfill({ json: { data: { answer: 'unexpected' } } }); });
    const canceled = await page.evaluate(async () => {
        const aiPath = '/src/api/ai.ts';
        const sessionPath = '/src/utils/authSession.ts';
        const { askFinanceAi } = await import(aiPath);
        const { readAuthSession, establishAuthSession } = await import(sessionPath);
        const pending = askFinanceAi('旧 A 私有问题', readAuthSession());
        establishAuthSession('c6-new-b-token', 72);
        try { await pending; return false; } catch (error) { return (error as { code: string }).code === 'ERR_CANCELED'; }
    });
    expect(canceled).toBe(true);
    expect(requests).toBe(0);
    await expectEmpty(page);
    expect((await auth(page)).userId).toBe('72');
});

for (const event of ['focus', 'visibilitychange']) {
    test(`C6 ${event} rechecks storage without relying on a storage event`, async ({ context, page }) => {
        await mockAuthAndReads(context);
        await login(page, userA);
        await context.route(aiPath, route => route.fulfill({ json: { data: { answer: 'A 回答' } } }));
        await submit(page, 'A 问题');
        await expect(page.locator('.ai-analyst__answer')).toHaveText('A 回答');
        await page.evaluate(event => {
            // Same-tab raw storage writes intentionally emit no storage event.
            localStorage.setItem('finance-os:auth-session:v1', 'c6-focus-new-session');
            (event === 'focus' ? window : document).dispatchEvent(new Event(event));
        }, event);
        await expectEmpty(page);
    });
}

test('C6 unmount invalidates pending results and a remounted page can submit', async ({ context, page }) => {
    const arrived = deferred(); const released = deferred();
    await mockAuthAndReads(context);
    await login(page, userA);
    await observeResponses(page);
    await context.route(aiPath, async route => {
        if (route.request().postDataJSON().question === '旧页面问题') {
            arrived.resolve(); await released.promise;
            return route.fulfill({ json: { data: { answer: '旧页面回答' } } });
        }
        return route.fulfill({ json: { data: { answer: '新页面回答' } } });
    });
    await submit(page, '旧页面问题'); await arrived.promise;
    await page.getByRole('link', { name: '投资', exact: true }).first().click();
    await expect(page).toHaveURL(/\/investments$/);
    await page.getByRole('link', { name: 'AI 分析', exact: true }).first().click();
    await expectEmpty(page);
    released.resolve(); await waitConsumed(page);
    await expectEmpty(page);
    await submit(page, '新页面问题');
    await expect(page.locator('.ai-analyst__answer')).toHaveText('新页面回答');
});

const investmentKey = 'c6-investment-intent';
const investmentStorageKey = `finance-os:investment-command:pending:v1:71:${investmentKey}`;
const investmentBody = '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}';

async function seedInvestment(page: Page) {
    await page.evaluate(({ key, bodyJson, idempotencyKey }) => {
        localStorage.setItem(key, JSON.stringify({
            schemaVersion: 1, userId: 71, state: 'OUTCOME_UNKNOWN', commandType: 'BUY', method: 'POST',
            path: '/investment/positions/44/buy', idempotencyKey, bodyJson, target: { assetId: 44 },
            submittedAt: '2026-10-11T00:00:00.000Z', updatedAt: '2026-10-11T00:00:00.000Z',
        }));
    }, { key: investmentStorageKey, bodyJson: investmentBody, idempotencyKey: investmentKey });
}

test('C6 non-AI Investment 401 preserves exact intent and same-user Login resumes once', async ({ context, page }) => {
    await mockAuthAndReads(context);
    await login(page, userA);
    await seedInvestment(page);
    const posts: Array<{ body: string | null; key: string | undefined }> = [];
    await context.route('**/api/v1/investment/positions/44/buy', route => {
        posts.push({ body: route.request().postData(), key: route.request().headers()['idempotency-key'] });
        return route.fulfill(posts.length === 1
            ? { status: 401, json: { code: 401, message: 'expired' } }
            : { json: { code: 200, data: { assetId: 44, transactionId: 99, idempotentReplay: true } } });
    });
    await context.route('**/api/v1/investment/positions/44', route => route.fulfill({ json: { code: 200, data: null } }));
    await page.goto('/investments');
    await page.getByRole('button', { name: '恢复原提交', exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(await auth(page)).toEqual({ token: null, userId: null, session: null });
    expect(await page.evaluate(key => JSON.parse(localStorage.getItem(key) ?? '{}'), investmentStorageKey))
        .toMatchObject({ state: 'AUTH_REQUIRED', resumeState: 'OUTCOME_UNKNOWN', bodyJson: investmentBody });
    await login(page, userA);
    await page.goto('/investments');
    await page.getByRole('button', { name: '恢复原提交', exact: true }).click();
    await expect(page.getByText(/已恢复此前提交结果/)).toBeVisible();
    await expect(page.getByText(/当前数据已从后端刷新/)).toBeVisible();
    expect(posts).toEqual(Array(2).fill({ body: investmentBody, key: investmentKey }));
    expect(await page.evaluate(key => localStorage.getItem(key), investmentStorageKey)).toBeNull();
});

test('C6 logout keeps Investment intent, B cannot recover A, A can see it again', async ({ context, page }) => {
    await mockAuthAndReads(context);
    await login(page, userA);
    await seedInvestment(page);
    const original = await page.evaluate(key => localStorage.getItem(key), investmentStorageKey);
    let posts = 0;
    await context.route('**/api/v1/investment/positions/44/buy', route => { posts++; return route.abort(); });
    const dialogs: string[] = [];
    page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.accept(); });
    await switchUser(page, userB);
    expect(dialogs).toHaveLength(1);
    expect(dialogs[0]).toContain('退出不会删除恢复记录');
    expect(await page.evaluate(key => localStorage.getItem(key), investmentStorageKey)).toBe(original);
    await page.goto('/investments');
    await expect(page.getByText('暂无符合条件的逻辑交易。', { exact: true })).toBeVisible();
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByRole('button', { name: '恢复原提交', exact: true })).toHaveCount(0);
    await switchUser(page, userA);
    await page.goto('/investments');
    await expect(page.getByRole('button', { name: '恢复原提交', exact: true })).toBeVisible();
    expect(await page.evaluate(key => localStorage.getItem(key), investmentStorageKey)).toBe(original);
    expect(posts).toBe(0);
});

test('C6 concurrent Login commit and old 401 share a cross-tab auth mutation lock', async ({ context, page }) => {
    const arrived = deferred(); const released = deferred();
    const otherTab = await openTwoTabs(context, page);
    await observeResponses(page);
    await context.route(aiPath, async route => {
        arrived.resolve(); await released.promise;
        await route.fulfill({ status: 401, json: { code: 401, message: 'old A unauthorized' } });
    });
    await submit(page, 'A 与登录竞争的旧问题'); await arrived.promise;
    await page.evaluate(() => new Promise<void>(resolve => {
        void navigator.locks.request('finance-os:auth-session-mutation:v1', async () => {
            await new Promise<void>(release => {
                (window as Window & { c6ReleaseAuthLock?: () => void }).c6ReleaseAuthLock = release;
                resolve();
            });
        });
    }));
    await otherTab.goto('/login');
    await otherTab.getByRole('textbox', { name: '用户名' }).fill(userB.username);
    await otherTab.getByLabel('密码', { exact: true }).fill('fictional-c6-password');
    await otherTab.getByRole('button', { name: '登录', exact: true }).click();
    await expect.poll(() => page.evaluate(async () => (await navigator.locks.query()).pending
        ?.filter(lock => lock.name === 'finance-os:auth-session-mutation:v1').length)).toBe(1);
    released.resolve();
    await expect.poll(() => page.evaluate(async () => (await navigator.locks.query()).pending
        ?.filter(lock => lock.name === 'finance-os:auth-session-mutation:v1').length)).toBe(2);
    await page.evaluate(() => (window as Window & { c6ReleaseAuthLock?: () => void }).c6ReleaseAuthLock?.());
    await expect(otherTab).toHaveURL(/\/investments$/);
    await waitConsumed(page);
    expect((await auth(otherTab)).userId).toBe(String(userB.userId));
    expect((await auth(otherTab)).token).toBeTruthy();
    expect((await auth(otherTab)).session).toBeTruthy();
    await expectEmpty(page);
    await expect(page).toHaveURL(/\/ai$/);
});

test('C6 Import pending blocks logout and retains pending/draft records', async ({ context, page }) => {
    await mockAuthAndReads(context);
    await login(page, userA);
    const key = 'finance-os:transaction-import:pending:v1:71:c6-fixture';
    const draftKey = 'finance-os:transaction-import:draft:v1:71';
    await page.evaluate(({ key, draftKey }) => {
        localStorage.setItem(key, 'c6-preserved-invalid-recovery-evidence');
        sessionStorage.setItem(draftKey, 'c6-preserved-draft');
    }, { key, draftKey });
    const before = await auth(page);
    await page.getByRole('button', { name: '退出登录', exact: true }).first().click();
    await expect(page).toHaveURL(/\/transactions\/import$/);
    expect(await auth(page)).toEqual(before);
    expect(await page.evaluate(({ key, draftKey }) => ({ pending: localStorage.getItem(key), draft: sessionStorage.getItem(draftKey) }), { key, draftKey }))
        .toEqual({ pending: 'c6-preserved-invalid-recovery-evidence', draft: 'c6-preserved-draft' });
});

type LockFault = 'missing' | 'no-request' | 'rejected' | 'throws' | 'getter-throws';
const compatibilityMessage = '当前环境无法安全保护 AI 会话，本次分析已停止。请使用新版浏览器或稍后重试。';

async function breakLocks(page: Page, mode: LockFault) {
    await page.evaluate(mode => {
        const state = window as Window & { c6OriginalLocks?: LockManager; c6OriginalLockRequest?: LockManager['request'] };
        state.c6OriginalLocks = navigator.locks;
        state.c6OriginalLockRequest = navigator.locks?.request;
        if (mode === 'missing') {
            Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
        } else if (mode === 'getter-throws') {
            Object.defineProperty(navigator, 'locks', { configurable: true, get: () => { throw new DOMException('C6 lock getter failure', 'SecurityError'); } });
        } else {
            Object.defineProperty(navigator.locks, 'request', { configurable: true, value: mode === 'no-request' ? undefined
                : mode === 'rejected' ? () => Promise.reject(new DOMException('C6 injected lock rejection', 'SecurityError'))
                    : () => { throw new DOMException('C6 injected lock failure', 'SecurityError'); } });
        }
    }, mode);
    // Verify the actual browser API boundary, not an application helper mock.
    const availability = await page.evaluate(() => {
        try { return { locks: !!navigator.locks, request: typeof navigator.locks?.request }; }
        catch { return { locks: 'throws', request: 'throws' }; }
    });
    expect(availability).toEqual(mode === 'getter-throws' ? { locks: 'throws', request: 'throws' }
        : { locks: mode !== 'missing', request: ['missing', 'no-request'].includes(mode) ? 'undefined' : 'function' });
}

async function restoreLocks(page: Page) {
    await page.evaluate(() => {
        const state = window as Window & { c6OriginalLocks?: LockManager; c6OriginalLockRequest?: LockManager['request'] };
        Object.defineProperty(navigator, 'locks', { configurable: true, value: state.c6OriginalLocks });
        Object.defineProperty(navigator.locks, 'request', { configurable: true, value: state.c6OriginalLockRequest });
    });
}

async function drainPage(page: Page) {
    await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
}

for (const mode of ['missing', 'no-request', 'rejected', 'throws', 'getter-throws'] as const) {
    test(`C6 compatibility ${mode} refuses AI sends and keeps auth and recovery records`, async ({ context, page }, testInfo) => {
        const otherTab = await openTwoTabs(context, page);
        let sends = 0;
        await context.route(aiPath, route => { sends++; return route.fulfill({ json: { data: { answer: '兼容恢复后的正常回答' } } }); });
        await seedInvestment(page);
        const before = await auth(page);
        const pendingBefore = await page.evaluate(key => localStorage.getItem(key), investmentStorageKey);
        await breakLocks(page, mode);
        await submit(page, 'A 无锁问题');
        await expect(page.getByRole('alert')).toHaveText(compatibilityMessage);
        await expect(page.getByRole('status')).toHaveCount(0);
        expect(sends).toBe(0);
        expect(await auth(otherTab)).toEqual(before);
        expect(await page.evaluate(key => localStorage.getItem(key), investmentStorageKey)).toBe(pendingBefore);
        await expect(page).toHaveURL(/\/ai$/);
        await page.screenshot({ path: testInfo.outputPath(`${mode}-safe-refusal.png`) });
        // Refusal must release its own UI lock; restore the real API and retry.
        await restoreLocks(page);
        await submit(page, 'A 恢复支持后问题');
        await expect(page.locator('.ai-analyst__answer')).toHaveText('兼容恢复后的正常回答');
        expect(sends).toBe(1);
    });

    test(`C6 compatibility ${mode} suppresses current-session 401 side effects and shows a safe message`, async ({ context, page }) => {
        const arrived = deferred(); const released = deferred();
        const otherTab = await openTwoTabs(context, page);
        await observeResponses(page);
        await page.evaluate(async () => {
            const path = '/src/api/index.ts'; const { default: api } = await import(path);
            (window as Window & { c6UnsafeCallbacks: number }).c6UnsafeCallbacks = 0;
            api.interceptors.request.use((config: { url: string; investmentCommandUnauthorized?: () => void; transactionImportUnauthorized?: () => void }) => {
                if (config.url === '/ai/ask') {
                    config.investmentCommandUnauthorized = config.transactionImportUnauthorized = () => {
                        (window as Window & { c6UnsafeCallbacks: number }).c6UnsafeCallbacks++;
                    };
                }
                return config;
            });
        });
        await context.route(aiPath, async route => {
            arrived.resolve(); await released.promise;
            await route.fulfill({ status: 401, json: { code: 401, message: '旧响应不得覆盖安全提示' } });
        });
        const before = await auth(page);
        await submit(page, '已发出后丢失锁的问题'); await arrived.promise;
        await breakLocks(page, mode);
        released.resolve(); await waitConsumed(page);
        await expect(page.getByRole('alert')).toHaveText(compatibilityMessage);
        expect(await page.evaluate(() => (window as Window & { c6UnsafeCallbacks: number }).c6UnsafeCallbacks)).toBe(0);
        await expect(page.getByRole('status')).toHaveCount(0);
        expect(await auth(otherTab)).toEqual(before);
        await expect(page).toHaveURL(/\/ai$/);
    });

    for (const status of [200, 503, 401]) {
        test(`C6 compatibility ${mode} ignores A old ${status} after B Login and keeps B pending lock`, async ({ context, page }) => {
            const arrived = deferred(); const released = deferred();
            const bArrived = deferred(); const bReleased = deferred();
            let sends = 0;
            await context.route(aiPath, async route => {
                sends++;
                if (route.request().postDataJSON().question === 'B 新请求') {
                    bArrived.resolve(); await bReleased.promise;
                    return route.fulfill({ json: { data: { answer: 'B 新会话安全回答' } } });
                }
                arrived.resolve(); await released.promise;
                return route.fulfill({ status, json: status === 200 ? { data: { answer: 'A 旧回答' } } : { code: status, message: 'A 旧错误' } });
            });
            const otherTab = await openTwoTabs(context, page);
            await observeResponses(page);
            await seedInvestment(page);
            otherTab.on('dialog', dialog => dialog.accept());
            await submit(page, 'A 旧请求'); await arrived.promise;
            await breakLocks(page, mode);
            await switchUser(otherTab, userB);
            const bAuth = await auth(otherTab);
            const record = await page.evaluate(key => localStorage.getItem(key), investmentStorageKey);
            await expectEmpty(page);
            await restoreLocks(page);
            await submit(page, 'B 新请求'); await bArrived.promise;
            await breakLocks(page, mode);
            released.resolve(); await waitConsumed(page);
            await expect(page.locator('.ai-analyst__answer')).toHaveCount(0);
            await expect(page.getByRole('alert')).toHaveCount(0);
            await expect(page.getByRole('status')).toHaveText('正在分析…');
            await expect(page.getByRole('textbox', { name: '问题', exact: true })).toHaveValue('B 新请求');
            expect(await auth(otherTab)).toEqual(bAuth);
            await expect(page).toHaveURL(/\/ai$/);
            expect(await page.evaluate(key => localStorage.getItem(key), investmentStorageKey)).toBe(record);
            await page.locator('form.ai-analyst__composer').evaluate(form => (form as HTMLFormElement).requestSubmit());
            await drainPage(page);
            expect(sends).toBe(2);
            bReleased.resolve();
            await expect(page.locator('.ai-analyst__answer')).toHaveText('B 新会话安全回答');
            await expect(page.getByRole('alert')).toHaveCount(0);
            expect(await auth(otherTab)).toEqual(bAuth);
            await submit(page, 'B 被安全拒绝的请求');
            await expect(page.getByRole('alert')).toHaveText(compatibilityMessage);
            expect(sends).toBe(2);
        });
    }
}

for (const transition of ['same-user', 'ABA']) {
    test(`C6 compatibility missing locks rejects stale 401 across ${transition} with identical token`, async ({ context, page }) => {
        const arrived = deferred(); const released = deferred();
        const otherTab = await openTwoTabs(context, page, true);
        const before = await auth(page);
        await observeResponses(page);
        await context.route(aiPath, async route => {
            arrived.resolve(); await released.promise;
            await route.fulfill({ status: 401, json: { code: 401, message: '旧 A 401' } });
        });
        await submit(page, '旧 A 相同 token 请求'); await arrived.promise;
        await breakLocks(page, 'missing');
        await otherTab.addInitScript(() => Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined }));
        await breakLocks(otherTab, 'missing');
        if (transition === 'ABA') await switchUser(otherTab, userB);
        await switchUser(otherTab, userA);
        const current = await auth(otherTab);
        expect(current.token).toBe(before.token);
        expect(current.userId).toBe(before.userId);
        expect(current.session).not.toBe(before.session);
        released.resolve(); await waitConsumed(page);
        await expectEmpty(page);
        expect(await auth(otherTab)).toEqual(current);
        await submit(page, '新 A 不安全环境请求');
        await expect(page.getByRole('alert')).toHaveText(compatibilityMessage);
        expect(await auth(otherTab)).toEqual(current);
    });
}

test('C6 direct Axios AI calls cannot omit session guard in a lockless browser', async ({ context, page }) => {
    await openTwoTabs(context, page);
    let sends = 0;
    await context.route('**/api/v1/ai/ask**', route => { sends++; return route.fulfill({ json: { data: { answer: 'unexpected' } } }); });
    await breakLocks(page, 'missing');
    const before = await auth(page);
    const codes = await page.evaluate(async () => {
        const path = '/src/api/index.ts'; const { default: api } = await import(path);
        const codes = [];
        for (const config of [{ url: '/ai/ask' }, { url: 'ai/ask' }, { url: '/api/v1/ai/ask', baseURL: '' },
            { url: `${location.origin}/api/v1/ai/ask?c6=direct` }, { url: '/ask', baseURL: '/api/v1/ai' }]) {
            try { await api.post(config.url, { question: '不能绕过 AI 安全边界' }, config); codes.push('sent'); }
            catch (error) { codes.push((error as { code: string }).code); }
        }
        return codes;
    });
    expect(codes).toEqual(Array(5).fill('AI_AUTH_SAFETY_UNAVAILABLE'));
    expect(sends).toBe(0);
    expect(await auth(page)).toEqual(before);
});

for (const mode of ['missing', 'no-request'] as const) {
    test(`C6 non-AI 401 and real Login keep compatibility for ${mode} without losing recovery records`, async ({ context, page }) => {
        const otherTab = await openTwoTabs(context, page);
        await seedInvestment(page);
        const importKey = 'finance-os:transaction-import:pending:v1:99:c6-unrelated';
        await page.evaluate(key => localStorage.setItem(key, 'C6 unrelated Import evidence'), importKey);
        const pending = await page.evaluate(key => localStorage.getItem(key), investmentStorageKey);
        await page.addInitScript(mode => {
            if (mode === 'missing') Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined });
            else Object.defineProperty(navigator.locks, 'request', { configurable: true, value: undefined });
        }, mode);
        await breakLocks(page, mode);
        let reads = 0;
        await context.route('**/api/v1/accounts/page**', route => route.fulfill(++reads === 1
            ? { status: 401, json: { code: 401, message: 'C6 ordinary auth expiration' } }
            : { json: { code: 200, data: [] } }));
        await page.getByRole('link', { name: '账户', exact: true }).first().click();
        await expect(page).toHaveURL(/\/login$/);
        expect(await auth(otherTab)).toEqual({ token: null, userId: null, session: null });
        expect(await page.evaluate(key => localStorage.getItem(key), investmentStorageKey)).toBe(pending);
        expect(await page.evaluate(key => localStorage.getItem(key), importKey)).toBe('C6 unrelated Import evidence');
        await login(page, userA);
        expect((await auth(page)).userId).toBe(String(userA.userId));
        expect((await auth(page)).session).toBeTruthy();
        await submit(page, '无锁下只有 AI 被安全拒绝');
        await expect(page.getByRole('alert')).toHaveText(compatibilityMessage);
        expect(await page.evaluate(key => localStorage.getItem(key), investmentStorageKey)).toBe(pending);
    });
}
