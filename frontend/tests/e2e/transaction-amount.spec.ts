import { expect, test, type Page } from '@playwright/test';

const amounts = ['0.01', '1.00', '1234.56', '99999999999999.99', '100000000000000.01', '100000000000000.02', '100000000000000.03', '9999999999999999.99'];
type Fact = { id: number; type: string; amount: string; description: string };

// Keep amount as a raw JSON numeric token, just like Jackson's BigDecimal response.
function rawFact(fact: Fact) {
    return `{"id":${fact.id},"accountId":1,"categoryId":${fact.type === 'EXPENSE' ? 8 : 9},"type":"${fact.type}","amount":${fact.amount},"currency":"CNY","description":${JSON.stringify(fact.description)},"transactedAt":"2026-10-10T10:00:00"}`;
}

async function installApi(page: Page) {
    let fact: Fact | null = null;
    let rejectWrite = false;
    const writes: Array<{ method: string; body: string; key?: string }> = [];
    await page.addInitScript(() => { localStorage.setItem('token', 'test-token'); });
    await page.route('**/api/v1/accounts', route => route.fulfill({ json: { code: 200, message: 'ok', data: [{ id: 1, name: '现金', status: 'ACTIVE' }] } }));
    await page.route('**/api/v1/categories', route => route.fulfill({ json: { code: 200, message: 'ok', data: [{ id: 8, name: '餐饮', type: 'EXPENSE' }, { id: 9, name: '工资', type: 'INCOME' }] } }));
    await page.route('**/api/v1/transactions/page?*', route => route.fulfill({ contentType: 'application/json', body: `{"code":200,"message":"ok","data":{"records":[${fact ? rawFact(fact) : ''}],"total":${fact ? 1 : 0},"page":1,"size":20}}` }));
    await page.route(/\/api\/v1\/transactions(?:\/\d+)?$/, route => {
        const request = route.request();
        if (request.method() === 'DELETE') { fact = null; return route.fulfill({ json: { code: 200, message: 'ok' } }); }
        const body = request.postData() ?? '';
        writes.push({ method: request.method(), body, key: request.headers()['idempotency-key'] });
        if (rejectWrite) return route.fulfill({ status: 400, json: { code: 400, message: '流水金额不合法' } });
        const payload = JSON.parse(body);
        fact = { id: 1, type: payload.type, amount: String(payload.amount), description: payload.description ?? '' };
        return route.fulfill({ contentType: 'application/json', body: `{"code":200,"message":"ok","data":${rawFact(fact)}}` });
    });
    await page.goto('/transactions');
    await expect(page.getByText('暂无流水')).toBeVisible();
    return { writes, rejectNextWrite: () => { rejectWrite = true; } };
}

async function openCreate(page: Page, type: string, amount: string) {
    await page.getByRole('button', { name: '新增流水', exact: true }).click();
    const form = page.locator('form').filter({ has: page.getByRole('heading', { name: '新增流水' }) });
    await form.getByRole('combobox', { name: '类型', exact: true }).selectOption(type);
    await form.getByRole('combobox', { name: '账户', exact: true }).selectOption('1');
    await form.getByRole('combobox', { name: '分类', exact: true }).selectOption(type === 'EXPENSE' ? '8' : '9');
    await form.getByLabel('金额', { exact: true }).fill(amount);
    await form.getByLabel('说明', { exact: true }).fill('精度验证');
    return form;
}

test('create preserves every legal decimal in actual browser HTTP request bytes', async ({ page }) => {
    const { writes } = await installApi(page);
    for (const amount of amounts) {
        const form = await openCreate(page, 'INCOME', amount);
        await form.getByRole('button', { name: '保存', exact: true }).click();
        await expect(page.getByText('流水已创建')).toBeVisible();
        expect(JSON.parse(writes.at(-1)!.body).amount).toBe(amount);
        expect(writes.at(-1)!.body).toContain(`"amount":"${amount}"`);
        await page.getByRole('button', { name: '编辑', exact: true }).click();
        await expect(page.getByLabel('金额', { exact: true })).toHaveValue(amount);
        if (amount === '100000000000000.01') console.log(`C7_BROWSER_EVIDENCE input=${amount}; request=${writes.at(-1)!.body}; edit=${await page.getByLabel('金额', { exact: true }).inputValue()}`);
        await page.getByRole('button', { name: '取消编辑' }).click();
    }
});

test('raw numeric reads, repeated edits and delete preserve exact amount and list display for all types', async ({ page }) => {
    const { writes } = await installApi(page);
    for (const [type, sign] of [['INCOME', ''], ['EXPENSE', ''], ['ADJUSTMENT', ''], ['ADJUSTMENT', '-']] as const) {
        const form = await openCreate(page, type, `${sign}100000000000000.01`);
        await form.getByRole('button', { name: '保存', exact: true }).click();
        await expect(page.getByText('流水已创建')).toBeVisible();
        await expect(page.locator('td.cell-number')).toHaveText(`${type === 'EXPENSE' || sign === '-' ? '-' : ''}¥100,000,000,000,000.01`);
        let previous = `${sign}100000000000000.01`;
        for (const amount of ['100000000000000.02', '100000000000000.03', '100000000000000.01']) {
            await page.getByRole('button', { name: '编辑', exact: true }).click();
            const edit = page.locator('form').filter({ has: page.getByRole('heading', { name: '编辑流水' }) });
            await expect(edit.getByLabel('金额', { exact: true })).toHaveValue(previous);
            await edit.getByLabel('金额', { exact: true }).fill(`${sign}${amount}`);
            await edit.getByRole('button', { name: '保存修改' }).click();
            await expect(page.getByText('流水已更新')).toBeVisible();
            expect(JSON.parse(writes.at(-1)!.body).amount).toBe(`${sign}${amount}`);
            expect(writes.at(-1)!.method).toBe('PUT');
            await expect(page.locator('td.cell-number')).toContainText(amount.slice(-3));
            previous = `${sign}${amount}`;
        }
        page.once('dialog', dialog => dialog.accept());
        await page.getByRole('button', { name: '删除', exact: true }).click();
        await expect(page.getByText('暂无流水')).toBeVisible();
    }
});

test('invalid decimal input never writes and existing validation messages remain visible', async ({ page }) => {
    const { writes } = await installApi(page);
    let form = await openCreate(page, 'INCOME', '1.00');
    for (const [value, message] of [
        ['abc', '请输入有效金额'], ['1.2.3', '请输入有效金额'], ['1.001', '金额最多支持两位小数'],
        ['1.000', '金额最多支持两位小数'], ['10000000000000000.00', '金额超出允许范围'],
        ['-1.00', '收入和支出金额必须大于 0'], ['0', '收入和支出金额必须大于 0'],
    ]) {
        await form.getByLabel('金额', { exact: true }).fill(value!);
        await form.getByRole('button', { name: '保存', exact: true }).click();
        await expect(page.getByText(message!, { exact: true })).toBeVisible();
    }
    await form.getByLabel('金额', { exact: true }).fill('');
    await form.getByRole('button', { name: '保存', exact: true }).click();
    expect(await form.getByLabel('金额', { exact: true }).evaluate((input: HTMLInputElement) => input.validity.valueMissing)).toBe(true);
    expect(writes).toHaveLength(0);
    await page.getByRole('button', { name: '收起表单' }).click();
    form = await openCreate(page, 'ADJUSTMENT', '0.00');
    await form.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('调整金额不能为 0', { exact: true })).toBeVisible();
    await form.getByLabel('金额', { exact: true }).fill('-0.01');
    await form.getByLabel('说明', { exact: true }).fill('');
    await form.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('调整流水必须填写说明', { exact: true })).toBeVisible();
    expect(writes).toHaveLength(0);
});

test('server rejection keeps exact entered amount and surfaces the backend error', async ({ page }) => {
    const fixture = await installApi(page);
    const form = await openCreate(page, 'INCOME', '100000000000000.01');
    fixture.rejectNextWrite();
    await form.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByText('流水金额不合法', { exact: true })).toBeVisible();
    await expect(form.getByLabel('金额', { exact: true })).toHaveValue('100000000000000.01');
    expect(JSON.parse(fixture.writes[0]!.body).amount).toBe('100000000000000.01');
});

test('equivalent decimal and exponent input keeps the retry intent while distinct cents change it', async ({ page }) => {
    const fixture = await installApi(page);
    const form = await openCreate(page, 'INCOME', '10000000000000001e-2');
    fixture.rejectNextWrite();
    for (const amount of ['10000000000000001e-2', '+100000000000000.01', '100000000000000.02']) {
        await form.getByLabel('金额', { exact: true }).fill(amount);
        await form.getByRole('button', { name: '保存', exact: true }).click();
        await expect.poll(() => fixture.writes.length).toBe(['10000000000000001e-2', '+100000000000000.01', '100000000000000.02'].indexOf(amount) + 1);
        await expect(page.getByText('流水金额不合法', { exact: true })).toBeVisible();
    }
    expect(fixture.writes.map(write => JSON.parse(write.body).amount)).toEqual(['100000000000000.01', '100000000000000.01', '100000000000000.02']);
    expect(fixture.writes[0]!.key).toBeTruthy();
    expect(fixture.writes[1]!.key).toBe(fixture.writes[0]!.key);
    expect(fixture.writes[2]!.key).not.toBe(fixture.writes[0]!.key);
});
