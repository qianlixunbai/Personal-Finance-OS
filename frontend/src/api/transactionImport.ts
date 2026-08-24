import api from './index.ts';
import type { TransactionImportMapping, TransactionImportPreviewResponse, TransactionImportPreviewRow } from '../types/transactionImport';

function jsonPart(value: unknown) { return new Blob([JSON.stringify(value)], { type: 'application/json' }); }

export async function uploadTransactionImport(file: File): Promise<TransactionImportPreviewResponse> {
    const form = new FormData();
    form.append('file', file);
    form.append('request', jsonPart({ format: 'AUTO', mapping: null }));
    const response = await api.post('/imports/transactions/preview', form);
    return response.data.data as TransactionImportPreviewResponse;
}

export async function updateTransactionImportMapping(sessionId: string, mapping: TransactionImportMapping): Promise<TransactionImportPreviewResponse> {
    const form = new FormData();
    form.append('mapping', jsonPart(mapping));
    const response = await api.put(`/imports/transactions/${sessionId}/preview`, form);
    return response.data.data as TransactionImportPreviewResponse;
}

export async function fetchTransactionImportRows(sessionId: string, page: number, size: number, signal?: AbortSignal): Promise<TransactionImportPreviewRow[]> {
    const response = await api.get(`/imports/transactions/${sessionId}/rows`, { params: { page, size }, signal });
    return (response.data.data ?? []) as TransactionImportPreviewRow[];
}

export async function cancelTransactionImport(sessionId: string) {
    await api.delete(`/imports/transactions/${sessionId}`);
}

export async function fetchTransactionImportOptions() {
    const [accounts, categories] = await Promise.all([api.get('/accounts'), api.get('/categories')]);
    return { accounts: accounts.data.data ?? [], categories: categories.data.data ?? [] };
}
