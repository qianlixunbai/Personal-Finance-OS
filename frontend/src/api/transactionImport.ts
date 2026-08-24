import api from './index.ts';
import type { PendingTransactionImportV1, TransactionImportConfirmResponse, TransactionImportMapping, TransactionImportPreviewResponse, TransactionImportPreviewRow, TransactionImportReceipt } from '../types/transactionImport';
import { decodeTransactionImportReceiptGetResponse, decodeTransactionImportReceiptResponse } from '../utils/transactionImportTransport.ts';
import type { CommandAwareRequestConfig } from './index.ts';

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

const rawTextConfig = (onUnauthorized?: () => void, transactionImportAuthRecord?: PendingTransactionImportV1): CommandAwareRequestConfig => ({
    responseType: 'text',
    transformResponse: [(value: string) => value],
    transactionImportUnauthorized: onUnauthorized,
    transactionImportAuthRecord,
});

export async function confirmTransactionImport(sessionId: string, bodyJson: string, idempotencyKey: string, onUnauthorized?: () => void, transactionImportAuthRecord?: PendingTransactionImportV1): Promise<TransactionImportConfirmResponse> {
    try {
        const response = await api.post(`/imports/transactions/${sessionId}/confirm`, bodyJson, {
            ...rawTextConfig(onUnauthorized, transactionImportAuthRecord),
            headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        });
        const decoded = decodeTransactionImportReceiptResponse(response.data as string);
        if (decoded.code !== 200 || !decoded.data) throw new Error(decoded.message || '确认导入响应不完整。');
        return decoded.data;
    } catch (error) {
        if ((error as { response?: { status?: number } }).response?.status === 401) onUnauthorized?.();
        throw error;
    }
}

export async function fetchTransactionImportReceipt(batchId: string, onUnauthorized?: () => void): Promise<TransactionImportReceipt> {
    try {
        const response = await api.get(`/imports/transactions/batches/${batchId}`, rawTextConfig(onUnauthorized));
        const decoded = decodeTransactionImportReceiptGetResponse(response.data as string);
        if (decoded.code !== 200 || !decoded.data) throw new Error(decoded.message || '回执查询响应不完整。');
        return decoded.data;
    } catch (error) {
        if ((error as { response?: { status?: number } }).response?.status === 401) onUnauthorized?.();
        throw error;
    }
}

export async function fetchTransactionImportOptions() {
    const [accounts, categories] = await Promise.all([api.get('/accounts'), api.get('/categories')]);
    return { accounts: accounts.data.data ?? [], categories: categories.data.data ?? [] };
}
