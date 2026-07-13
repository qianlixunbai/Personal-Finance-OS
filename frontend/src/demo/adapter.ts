import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import {
    demoCategories,
    demoDashboard,
    initialDemoAccounts,
    initialDemoAssets,
    initialDemoTransactions,
    type DemoAccount,
    type DemoAsset,
    type DemoTransaction,
} from './data.ts';

export const isDemoMode = true;

let accounts = structuredClone(initialDemoAccounts);
let assets = structuredClone(initialDemoAssets);
let transactions = structuredClone(initialDemoTransactions);

function response(config: InternalAxiosRequestConfig, data: unknown) {
    return { data: { data }, status: 200, statusText: 'OK', headers: {}, config };
}

function payload(config: InternalAxiosRequestConfig): Record<string, unknown> {
    if (!config.data) return {};
    return typeof config.data === 'string' ? JSON.parse(config.data) : config.data as Record<string, unknown>;
}

function pathOf(url = '') {
    return new URL(url, 'https://demo.local').pathname;
}

function pageOf<T>(records: T[], params?: Record<string, unknown>) {
    const page = Number(params?.page ?? 1);
    const size = Number(params?.size ?? 20);
    return { records: records.slice((page - 1) * size, page * size), total: records.length, page, size };
}

function findId(path: string) {
    return Number(path.split('/')[2]);
}

function nextId(items: Array<{ id: number }>) {
    return Math.max(0, ...items.map(item => item.id)) + 1;
}

function sortTransactions(items: DemoTransaction[]) {
    return [...items].sort((a, b) => b.transactedAt.localeCompare(a.transactedAt));
}

function handle(config: InternalAxiosRequestConfig) {
    const method = (config.method ?? 'get').toLowerCase();
    const path = pathOf(config.url);
    const params = config.params as Record<string, unknown> | undefined;
    const body = payload(config);

    if (method === 'post' && path === '/login') return { token: 'demo-static-token' };
    if (method === 'post' && path === '/register') return { registered: true };
    if (method === 'get' && path === '/dashboard') return structuredClone(demoDashboard);

    if (method === 'get' && path === '/accounts') return structuredClone(accounts);
    if (method === 'get' && path === '/accounts/page') return pageOf(structuredClone(accounts), params);
    if (method === 'post' && path === '/accounts') {
        const account: DemoAccount = { id: nextId(accounts), name: String(body.name), type: String(body.type), currency: String(body.currency || 'CNY'), balance: 0, status: 'ACTIVE', createdAt: new Date().toISOString() };
        accounts = [...accounts, account];
        return account;
    }
    if (method === 'put' && /^\/accounts\/\d+$/.test(path)) {
        const id = findId(path);
        accounts = accounts.map(account => account.id === id ? { ...account, ...body, id } as DemoAccount : account);
        return accounts.find(account => account.id === id);
    }
    if (method === 'post' && /^\/accounts\/\d+\/deactivate$/.test(path)) {
        const id = findId(path);
        accounts = accounts.map(account => account.id === id ? { ...account, status: 'INACTIVE' } : account);
        return accounts.find(account => account.id === id);
    }

    if (method === 'get' && path === '/assets/page') return pageOf(structuredClone(assets), params);
    if (method === 'get' && /^\/assets\/\d+$/.test(path)) return structuredClone(assets.find(asset => asset.id === findId(path)));
    if (method === 'post' && path === '/assets') {
        const quantity = Number(body.quantity || 0);
        const avgCost = Number(body.avgCost || 0);
        const asset: DemoAsset = { id: nextId(assets), name: String(body.name), symbol: String(body.symbol || ''), type: String(body.type || 'STOCK'), market: String(body.market || ''), currency: String(body.currency || 'CNY'), quantity, avgCost, currentPrice: avgCost, marketValue: quantity * avgCost, profitLoss: 0, profitLossRate: 0, createdAt: new Date().toISOString() };
        assets = [...assets, asset];
        return asset;
    }
    if (method === 'put' && /^\/assets\/\d+\/price$/.test(path)) {
        const id = findId(path);
        const price = Number(new URL(config.url ?? '', 'https://demo.local').searchParams.get('price'));
        assets = assets.map(asset => asset.id === id ? { ...asset, currentPrice: price, marketValue: asset.quantity * price, profitLoss: asset.quantity * (price - asset.avgCost), profitLossRate: asset.avgCost ? ((price - asset.avgCost) / asset.avgCost) * 100 : 0 } : asset);
        return assets.find(asset => asset.id === id);
    }
    if (method === 'put' && /^\/assets\/\d+\/close$/.test(path)) {
        const id = findId(path);
        assets = assets.map(asset => asset.id === id ? { ...asset, quantity: 0, marketValue: 0 } : asset);
        return assets.find(asset => asset.id === id);
    }
    if (method === 'delete' && /^\/assets\/\d+$/.test(path)) {
        assets = assets.filter(asset => asset.id !== findId(path));
        return null;
    }

    if (method === 'get' && path === '/categories') return structuredClone(demoCategories);
    if (method === 'get' && path === '/transactions/page') {
        let records = sortTransactions(transactions);
        if (params?.type) records = records.filter(item => item.type === params.type);
        if (params?.accountId) records = records.filter(item => item.accountId === Number(params.accountId));
        if (params?.categoryId) records = records.filter(item => item.categoryId === Number(params.categoryId));
        if (params?.start) records = records.filter(item => item.transactedAt >= String(params.start));
        if (params?.end) records = records.filter(item => item.transactedAt <= String(params.end));
        return pageOf(records, params);
    }
    if (method === 'post' && path === '/transactions') {
        const transaction: DemoTransaction = { id: nextId(transactions), accountId: Number(body.accountId), categoryId: Number(body.categoryId), type: body.type as DemoTransaction['type'], amount: Number(body.amount), currency: String(body.currency || 'CNY'), description: body.description ? String(body.description) : undefined, transactedAt: String(body.transactedAt) };
        transactions = [transaction, ...transactions];
        return transaction;
    }
    if (method === 'put' && /^\/transactions\/\d+$/.test(path)) {
        const id = findId(path);
        transactions = transactions.map(transaction => transaction.id === id ? { ...transaction, ...body, id, accountId: Number(body.accountId), categoryId: Number(body.categoryId), amount: Number(body.amount) } as DemoTransaction : transaction);
        return transactions.find(transaction => transaction.id === id);
    }
    if (method === 'delete' && /^\/transactions\/\d+$/.test(path)) {
        transactions = transactions.filter(transaction => transaction.id !== findId(path));
        return null;
    }

    throw new Error(`Unsupported demo endpoint: ${method.toUpperCase()} ${path}`);
}

export const demoAdapter: AxiosAdapter = async (config) => response(config, handle(config));
