import assert from 'node:assert/strict';
import test from 'node:test';
import api from '../src/api/index.ts';
import { demoAdapter } from '../src/demo/adapter.ts';

function installBrowserState() {
    Object.defineProperty(globalThis, 'localStorage', {
        configurable: true,
        value: { getItem: () => null, removeItem: () => undefined },
    });
}

async function demoDataSnapshot() {
    const [accounts, assets, transactions] = await Promise.all([
        api.get('/accounts'),
        api.get('/assets/page', { params: { page: 1, size: 20 } }),
        api.get('/transactions/page', { params: { page: 1, size: 20 } }),
    ]);
    return [accounts.data.data, assets.data.data.records, transactions.data.data.records];
}

test('demo adapter serves every read-only page data source without a network adapter', async () => {
    installBrowserState();

    assert.equal(api.defaults.adapter, demoAdapter);

    const [dashboard, accounts, accountPage, assetPage, assetDetail, transactions, categories] = await Promise.all([
        api.get('/dashboard'),
        api.get('/accounts'),
        api.get('/accounts/page', { params: { page: 1, size: 20 } }),
        api.get('/assets/page', { params: { page: 1, size: 20 } }),
        api.get('/assets/1'),
        api.get('/transactions/page', { params: { page: 1, size: 20, type: 'EXPENSE' } }),
        api.get('/categories'),
    ]);

    assert.equal(dashboard.data.data.monthlyCashFlowTrend.length, 6);
    assert.ok(accounts.data.data.length >= 3);
    assert.ok(accountPage.data.data.records.length >= 3);
    assert.ok(assetPage.data.data.records.length >= 3);
    assert.equal(assetDetail.data.data.id, 1);
    assert.ok(transactions.data.data.records.every((item: { type: string }) => item.type === 'EXPENSE'));
    assert.ok(categories.data.data.length >= 3);
});

test('demo adapter rejects unknown and all business write endpoints without mutating sample data', async () => {
    installBrowserState();
    const before = await demoDataSnapshot();

    await assert.rejects(api.get('/not-a-demo-endpoint'), /Demo is read-only/);

    const writes = [
        api.post('/register', { username: 'demo', email: 'demo@example.com', password: 'password' }),
        api.post('/accounts', { name: '新账户', type: 'BANK', currency: 'CNY' }),
        api.put('/accounts/1', { name: '编辑账户', type: 'BANK', currency: 'CNY' }),
        api.post('/accounts/1/deactivate'),
        api.post('/assets', { name: '新资产', quantity: 1, avgCost: 1 }),
        api.put('/assets/1/price?price=10'),
        api.put('/assets/1/close'),
        api.delete('/assets/1'),
        api.post('/transactions', { accountId: 1, categoryId: 3, type: 'EXPENSE', amount: 1, currency: 'CNY', transactedAt: '2026-07-13T12:00:00' }),
        api.put('/transactions/1008', { accountId: 1, categoryId: 3, type: 'EXPENSE', amount: 2, currency: 'CNY', transactedAt: '2026-07-13T12:00:00' }),
        api.delete('/transactions/1008'),
    ];

    for (const request of writes) {
        await assert.rejects(request, /Demo is read-only/);
    }

    assert.deepEqual(await demoDataSnapshot(), before);
});
