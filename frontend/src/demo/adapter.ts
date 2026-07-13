import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import {
    demoCategories,
    demoDashboard,
    initialDemoAccounts,
    initialDemoAssets,
    initialDemoTransactions,
    type DemoTransaction,
} from './data.ts';

export const isDemoMode = true;

const accounts = structuredClone(initialDemoAccounts);
const assets = structuredClone(initialDemoAssets);
const transactions = structuredClone(initialDemoTransactions);

function response(config: InternalAxiosRequestConfig, data: unknown) {
    return { data: { data }, status: 200, statusText: 'OK', headers: {}, config };
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

function sortTransactions(items: DemoTransaction[]) {
    return [...items].sort((a, b) => b.transactedAt.localeCompare(a.transactedAt));
}

function handle(config: InternalAxiosRequestConfig) {
    const method = (config.method ?? 'get').toLowerCase();
    const path = pathOf(config.url);
    const params = config.params as Record<string, unknown> | undefined;

    if (method !== 'get') {
        throw new Error(`Demo is read-only: ${method.toUpperCase()} ${path} is unavailable in the static demo`);
    }

    if (method === 'get' && path === '/dashboard') return structuredClone(demoDashboard);

    if (method === 'get' && path === '/accounts') return structuredClone(accounts);
    if (method === 'get' && path === '/accounts/page') return pageOf(structuredClone(accounts), params);

    if (method === 'get' && path === '/assets/page') return pageOf(structuredClone(assets), params);
    if (method === 'get' && /^\/assets\/\d+$/.test(path)) return structuredClone(assets.find(asset => asset.id === findId(path)));

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
    throw new Error(`Demo is read-only: unsupported endpoint ${method.toUpperCase()} ${path}`);
}

export const demoAdapter: AxiosAdapter = async (config) => response(config, handle(config));
