import assert from 'node:assert/strict';
import test from 'node:test';
import api from '../src/api/index.ts';

function installBrowserState() {
    Object.defineProperty(globalThis, 'localStorage', {
        configurable: true,
        value: { getItem: () => null, removeItem: () => undefined },
    });
}

test('static demo adapter serves dashboard data without a backend request', async () => {
    installBrowserState();

    const response = await api.get('/dashboard');
    const dashboard = response.data.data;

    assert.equal(typeof api.defaults.adapter, 'function');
    assert.equal(dashboard.monthlyCashFlowTrend.length, 6);
    assert.deepEqual(dashboard.monthlyCashFlowTrend[2], { month: '2026-04', income: 0, expense: 0, net: 0 });
    assert.equal(dashboard.monthNet, 11571.6);
});

test('static demo adapter supplies accounts, assets, and transactions', async () => {
    installBrowserState();

    const [accounts, assets, transactions] = await Promise.all([
        api.get('/accounts/page', { params: { page: 1, size: 20 } }),
        api.get('/assets/page', { params: { page: 1, size: 20 } }),
        api.get('/transactions/page', { params: { page: 1, size: 20 } }),
    ]);

    assert.ok(accounts.data.data.records.length >= 3);
    assert.ok(assets.data.data.records.length >= 3);
    assert.ok(transactions.data.data.records.some((item: { type: string }) => item.type === 'INCOME'));
    assert.ok(transactions.data.data.records.some((item: { type: string }) => item.type === 'EXPENSE'));
});
