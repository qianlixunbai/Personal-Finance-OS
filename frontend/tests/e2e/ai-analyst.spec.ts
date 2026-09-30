import { expect, test, type Page } from '@playwright/test';

const authToken = 'ai-analyst-e2e-token';
const authUserId = '7';
const aiPath = '**/api/v1/ai/ask';

async function authenticate(page: Page) {
    await page.addInitScript(({ token, userId }) => {
        const fixtureMarker = 'finance-os:e2e-auth-initialized';
        if (sessionStorage.getItem(fixtureMarker) === 'true') return;
        sessionStorage.setItem(fixtureMarker, 'true');
        localStorage.setItem('token', token);
        localStorage.setItem('finance-os:auth-user-id:v1', userId);
    }, { token: authToken, userId: authUserId });
}

async function openAiFromDashboard(page: Page) {
    await authenticate(page);
    await page.route('**/api/v1/dashboard', route => route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: 'ok', data: {
            totalAssets: 0,
            netWorth: 0,
            monthIncome: 0,
            monthExpense: 0,
            monthNet: 0,
            assetAllocation: [],
            monthlyCashFlowTrend: [],
            recentTransactions: [],
        } }),
    }));
    await page.goto('/');
    const aiLink = page.getByRole('link', { name: /AI 分析|AI 助手/ }).first();
    await expect(aiLink).toBeVisible();
    await aiLink.click();
    await expect(page).toHaveURL(/\/ai$/);
    await expect(page.getByRole('heading', { name: 'AI 财务分析', exact: true })).toBeVisible();
}

function okResponse(answer: string) {
    return { code: 200, message: 'ok', data: { answer } };
}

function errorResponse(status: number, message: string) {
    return {
        code: status,
        message,
        errorCode: 'AI_PROVIDER_FAILURE_INTERNAL',
        details: 'Provider credentials and tool execution details must not reach the page.',
    };
}

test('authenticated dashboard navigation submits a trimmed question through the JWT client and renders answer as text', async ({ page }) => {
    const requests: Array<{ authorization: string | undefined; body: unknown }> = [];
    const answer = '### 只读分析\n<script>window.aiAnalystXss = true</script>\n<img src=x onerror="window.aiAnalystXss = true">\n| 项目 | 金额 |\n| --- | --- |\n| 现金 | 100 |';
    await page.route(aiPath, async route => {
        requests.push({ authorization: route.request().headers().authorization, body: route.request().postDataJSON() });
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(okResponse(answer)) });
    });
    await openAiFromDashboard(page);

    await expect(page.getByText('AI 回答基于系统当前只读财务数据生成，不会修改账户、交易、持仓或余额。')).toBeVisible();
    const question = page.getByRole('textbox', { name: '问题' });
    await question.fill('   ');
    await question.evaluate(element => element.closest('form')?.requestSubmit());
    expect(requests).toHaveLength(0);

    const suggestedQuestion = '我的财务概览怎么样？';
    await page.getByRole('button', { name: suggestedQuestion, exact: true }).click();
    await expect(question).toHaveValue(suggestedQuestion);
    expect(requests).toHaveLength(0);

    await question.fill('  我这个月的现金流怎么样？  ');
    await page.getByRole('button', { name: '提交问题', exact: true }).click();
    const result = page.getByRole('region', { name: '分析结果' });
    const answerPanel = result.locator('.ai-analyst__answer');
    await expect(answerPanel).toContainText('<script>window.aiAnalystXss = true</script>');
    await expect(answerPanel).toContainText('<img src=x onerror="window.aiAnalystXss = true">');
    expect(await answerPanel.textContent()).toBe(answer);
    await expect(answerPanel.locator('img, script')).toHaveCount(0);
    expect(await page.evaluate(() => (window as Window & { aiAnalystXss?: boolean }).aiAnalystXss)).toBeUndefined();
    await expect(result).toContainText('我这个月的现金流怎么样？');
    expect(requests).toEqual([{
        authorization: `Bearer ${authToken}`,
        body: { question: '我这个月的现金流怎么样？' },
    }]);

    await page.reload();
    await expect(page.getByRole('heading', { name: '分析结果', exact: true })).toBeVisible();
    await expect(page.getByText(answer, { exact: true })).toHaveCount(0);
    await expect(question).toHaveValue('');
    expect(requests).toHaveLength(1);
    const storedState = await page.evaluate(() => ({
        local: Object.fromEntries(Object.keys(localStorage).sort().map(key => [key, localStorage.getItem(key)])),
        session: Object.fromEntries(Object.keys(sessionStorage).sort().map(key => [key, sessionStorage.getItem(key)])),
    }));
    expect(storedState).toEqual({
        local: { 'finance-os:auth-user-id:v1': authUserId, token: authToken },
        session: { 'finance-os:e2e-auth-initialized': 'true' },
    });
});

test('enforces 3000 characters, locks duplicate submits, retains the last success until replacement succeeds', async ({ page }) => {
    let requestCount = 0;
    let releaseSecond!: () => void;
    let releaseThird!: () => void;
    let markSecondArrived!: () => void;
    let markThirdArrived!: () => void;
    const secondArrived = new Promise<void>(resolve => { markSecondArrived = resolve; });
    const thirdArrived = new Promise<void>(resolve => { markThirdArrived = resolve; });
    const secondResponse = new Promise<void>(resolve => { releaseSecond = resolve; });
    const thirdResponse = new Promise<void>(resolve => { releaseThird = resolve; });

    await authenticate(page);
    await page.route(aiPath, async route => {
        requestCount += 1;
        if (requestCount === 1) {
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify(okResponse('第一次成功回答')) });
            return;
        }
        if (requestCount === 2) {
            markSecondArrived();
            await secondResponse;
            await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify(errorResponse(503, 'AI 服务暂时不可用')) });
            return;
        }
        if (requestCount === 3) {
            markThirdArrived();
            await thirdResponse;
            await route.fulfill({ contentType: 'application/json', body: JSON.stringify(okResponse('替换后的成功回答')) });
            return;
        }
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify(errorResponse(500, '服务器内部错误')) });
    });

    await page.goto('/ai');
    await expect(page.getByRole('heading', { name: 'AI 财务分析', exact: true })).toBeVisible();
    const question = page.getByRole('textbox', { name: '问题' });
    const result = page.getByRole('region', { name: '分析结果' });
    await expect(question).toHaveAttribute('maxlength', '3000');

    const fullQuestion = '字'.repeat(3000);
    await question.fill(fullQuestion);
    await expect(page.getByText('3000 / 3000', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '提交问题', exact: true }).click();
    await expect(result).toContainText('第一次成功回答');
    expect(requestCount).toBe(1);

    await question.fill('第二个问题');
    await page.getByRole('button', { name: '提交问题', exact: true }).click();
    await secondArrived;
    await expect(page.getByRole('status')).toHaveText('正在重新分析…');
    await expect(question).toBeDisabled();
    await expect(page.getByRole('button', { name: '正在分析…', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: '我的财务概览怎么样？', exact: true })).toBeDisabled();
    await expect(result).toContainText('第一次成功回答');
    await expect(result).toContainText(fullQuestion);
    await expect(result).toContainText('以下仍是上一次成功分析的结果');
    await page.locator('form.ai-analyst__composer').evaluate(form => (form as HTMLFormElement).requestSubmit());
    expect(requestCount).toBe(2);

    releaseSecond();
    await expect(page.getByRole('alert')).toHaveText('AI 服务暂时不可用');
    await expect(result).toContainText('第一次成功回答');
    await expect(result).toContainText(fullQuestion);

    await question.fill('第三个问题');
    await page.getByRole('button', { name: '提交问题', exact: true }).click();
    await thirdArrived;
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(result).toContainText('第一次成功回答');
    await expect(page.getByRole('status')).toHaveText('正在重新分析…');
    expect(requestCount).toBe(3);

    releaseThird();
    await expect(result).toContainText('替换后的成功回答');
    await expect(result).toContainText('第三个问题');
    await expect(result).not.toContainText('第一次成功回答');
    await expect(result).not.toContainText(fullQuestion);
    await expect(result.locator('.ai-analyst__answer')).toHaveCount(1);

    await question.evaluate(element => {
        const textarea = element as HTMLTextAreaElement;
        const nativeSetter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
        nativeSetter?.call(textarea, '超长'.repeat(1500) + 'x');
        textarea.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await expect(page.getByText('3001 / 3000', { exact: true })).toBeVisible();
    await page.locator('form.ai-analyst__composer').evaluate(form => (form as HTMLFormElement).requestSubmit());
    await expect(page.getByRole('alert')).toContainText('3000');
    expect(requestCount).toBe(3);
    await expect(result).toContainText('替换后的成功回答');
});

for (const { status, message } of [
    { status: 429, message: 'AI 请求过于频繁，请稍后重试' },
    { status: 502, message: 'AI 服务返回了无效响应' },
    { status: 503, message: 'AI 服务未启用' },
    { status: 500, message: '服务器内部错误' },
]) {
    test(`shows the safe backend message for ${status} without exposing provider details`, async ({ page }) => {
        let requestCount = 0;
        await authenticate(page);
        await page.route(aiPath, async route => {
            requestCount += 1;
            await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(errorResponse(status, message)) });
        });
        await page.goto('/ai');
        await page.getByRole('textbox', { name: '问题' }).fill('测试错误提示');
        await page.getByRole('button', { name: '提交问题', exact: true }).click();
        const alert = page.getByRole('alert');
        await expect(alert).toHaveText(message);
        await expect(alert).not.toContainText('AI_PROVIDER_FAILURE_INTERNAL');
        await expect(alert).not.toContainText('Provider credentials');
        expect(requestCount).toBe(1);
    });
}

test('redirects unauthenticated users from the protected analyst route to login', async ({ page }) => {
    await page.goto('/ai');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: '登录' })).toBeVisible();
});

test('the existing axios interceptor clears auth and redirects to login on 401', async ({ page }) => {
    let authorization: string | undefined;
    await authenticate(page);
    await page.route(aiPath, async route => {
        authorization = route.request().headers().authorization;
        await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 401, message: '登录已失效' }) });
    });
    await page.goto('/ai');
    await page.getByRole('textbox', { name: '问题' }).fill('触发登录失效');
    await page.getByRole('button', { name: '提交问题', exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(authorization).toBe(`Bearer ${authToken}`);
    await expect.poll(() => page.evaluate(() => ({ token: localStorage.getItem('token'), userId: localStorage.getItem('finance-os:auth-user-id:v1') })))
        .toEqual({ token: null, userId: null });
});

test('keeps the answer, suggested questions, and six navigation targets within desktop, tablet, and narrow mobile viewports', async ({ page }) => {
    const longAnswer = `${'X'.repeat(8000)}\n| category | amount |\n| --- | --- |\n| savings | 12345678901234567890.00 |`;
    await authenticate(page);
    await page.route(aiPath, route => route.fulfill({ contentType: 'application/json', body: JSON.stringify(okResponse(longAnswer)) }));
    await page.goto('/ai');
    await page.getByRole('textbox', { name: '问题' }).fill('测试窄屏长回答布局');
    await page.getByRole('button', { name: '提交问题', exact: true }).click();
    const answer = page.locator('.ai-analyst__answer');
    await expect(answer).toContainText('savings');

    for (const width of [1280, 820, 768, 390, 320]) {
        await page.setViewportSize({ width, height: 900 });
        await expect(page.getByRole('heading', { name: 'AI 财务分析', exact: true })).toBeVisible();
        const dimensions = await page.evaluate(() => ({
            viewport: window.innerWidth,
            document: document.documentElement.scrollWidth,
            body: document.body.scrollWidth,
            textarea: document.querySelector('textarea')?.getBoundingClientRect().width ?? 0,
            answerClient: document.querySelector('.ai-analyst__answer')?.clientWidth ?? 0,
            answerScroll: document.querySelector('.ai-analyst__answer')?.scrollWidth ?? 0,
        }));
        expect(dimensions.document, `document horizontal overflow at ${width}px`).toBeLessThanOrEqual(width);
        expect(dimensions.body, `body horizontal overflow at ${width}px`).toBeLessThanOrEqual(width);
        expect(dimensions.textarea, `textarea missing at ${width}px`).toBeGreaterThan(0);
        expect(dimensions.answerScroll, `answer text overflow at ${width}px`).toBeLessThanOrEqual(dimensions.answerClient);
        for (const suggestion of ['我的财务概览怎么样？', '我这个月的现金流怎么样？', '我的投资组合现在是什么情况？']) {
            await expect(page.getByRole('button', { name: suggestion, exact: true })).toBeVisible();
        }

        if (width <= 768) {
            const mobileNav = page.getByRole('navigation', { name: '移动端主导航' });
            await expect(mobileNav).toBeVisible();
            const links = mobileNav.getByRole('link');
            await expect(links).toHaveCount(6);
            const boxes = await links.evaluateAll(elements => elements.map(element => {
                const rect = element.getBoundingClientRect();
                return { x: rect.x, right: rect.right, width: rect.width, height: rect.height };
            }));
            for (const box of boxes) {
                expect(box.width, `mobile navigation target too narrow at ${width}px`).toBeGreaterThanOrEqual(44);
                expect(box.height, `mobile navigation target too short at ${width}px`).toBeGreaterThanOrEqual(44);
                expect(box.x).toBeGreaterThanOrEqual(0);
                expect(box.right).toBeLessThanOrEqual(width);
            }
            const mobileAiLink = mobileNav.getByRole('link', { name: /AI 分析|AI 助手/ });
            await expect(mobileAiLink).toBeVisible();
            if (width === 320) {
                await mobileAiLink.click();
                await expect(page).toHaveURL(/\/ai$/);
            }
        } else {
            const desktopNav = page.getByRole('navigation', { name: '主导航' });
            await expect(desktopNav.getByRole('link', { name: /AI 分析|AI 助手/ })).toBeVisible();
        }
    }
});
