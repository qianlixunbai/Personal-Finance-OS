import { isLosslessNumber, parse } from 'lossless-json';
import type { TransactionImportConfirmResponse, TransactionImportReceipt } from '../types/transactionImport';

export interface ImportApiResponse<T> { code: number; message: string; data?: T; errorCode?: string; retryable?: boolean; }

function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value); }
function safeInteger(value: unknown): number | null {
    if (typeof value === 'number' && Number.isSafeInteger(value)) return value;
    if (isLosslessNumber(value) && /^-?(?:0|[1-9]\d*)$/.test(value.toString())) {
        const parsed = Number(value.toString()); return Number.isSafeInteger(parsed) ? parsed : null;
    }
    return null;
}
function positiveInteger(value: unknown): value is number { const parsed = safeInteger(value); return parsed !== null && parsed > 0; }
function integer(value: unknown): value is number { const parsed = safeInteger(value); return parsed !== null && parsed >= 0; }
function text(value: unknown): value is string { return typeof value === 'string' && value.length > 0; }
function decimal(value: unknown): string | null {
    const lexeme = isLosslessNumber(value) ? value.toString() : typeof value === 'string' ? value : null;
    return lexeme && /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(lexeme) ? lexeme : null;
}

function receipt(value: unknown): TransactionImportReceipt {
    if (!isRecord(value) || !text(value.importSessionId) || !text(value.importBatchId) || value.status !== 'CONFIRMED'
        || !text(value.sourceFileName) || !text(value.fileDigest) || !integer(value.totalRows) || !integer(value.acceptedRows)
        || !integer(value.createdCount) || !integer(value.skippedCount) || !integer(value.warningCount) || !Array.isArray(value.transactions)
        || !Array.isArray(value.accountImpacts) || !text(value.confirmedAt) || !text(value.contractVersion) || !text(value.resultDigest)) throw new Error('服务端回执不符合冻结 Contract。');
    const transactions = value.transactions.map(item => {
        if (!isRecord(item) || !positiveInteger(item.rowNumber) || !positiveInteger(item.transactionId)) throw new Error('服务端回执交易引用无效。');
        return { rowNumber: safeInteger(item.rowNumber)!, transactionId: safeInteger(item.transactionId)! };
    });
    const accountImpacts = value.accountImpacts.map(item => {
        const before = isRecord(item) ? decimal(item.balanceBefore) : null; const delta = isRecord(item) ? decimal(item.delta) : null; const after = isRecord(item) ? decimal(item.balanceAfter) : null;
        if (!isRecord(item) || !positiveInteger(item.accountId) || !integer(item.rowCount) || !before || !delta || !after) throw new Error('服务端回执账户影响无效。');
        return { accountId: safeInteger(item.accountId)!, rowCount: safeInteger(item.rowCount)!, balanceBefore: before, delta, balanceAfter: after };
    });
    return { importSessionId: value.importSessionId, importBatchId: value.importBatchId, status: 'CONFIRMED', sourceFileName: value.sourceFileName, fileDigest: value.fileDigest, totalRows: safeInteger(value.totalRows)!, acceptedRows: safeInteger(value.acceptedRows)!, createdCount: safeInteger(value.createdCount)!, skippedCount: safeInteger(value.skippedCount)!, warningCount: safeInteger(value.warningCount)!, transactions, accountImpacts, confirmedAt: value.confirmedAt, contractVersion: value.contractVersion, resultDigest: value.resultDigest };
}

function envelope(raw: string): ImportApiResponse<unknown> {
    const value: unknown = parse(raw);
    if (!isRecord(value) || !integer(value.code) || typeof value.message !== 'string' || (value.errorCode !== undefined && typeof value.errorCode !== 'string') || (value.retryable !== undefined && typeof value.retryable !== 'boolean')) throw new Error('服务端响应不符合 API envelope。');
    return { code: safeInteger(value.code)!, message: value.message, data: value.data, errorCode: value.errorCode as string | undefined, retryable: value.retryable as boolean | undefined };
}

export function decodeTransactionImportReceiptResponse(raw: string): ImportApiResponse<TransactionImportConfirmResponse> {
    const parsed = envelope(raw); if (!parsed.data || !isRecord(parsed.data) || !isRecord(parsed.data.receipt) || typeof parsed.data.idempotentReplay !== 'boolean') throw new Error('Confirm 响应缺少权威回执。');
    return { ...parsed, data: { receipt: receipt(parsed.data.receipt), idempotentReplay: parsed.data.idempotentReplay } };
}

export function decodeTransactionImportReceiptGetResponse(raw: string): ImportApiResponse<TransactionImportReceipt> {
    const parsed = envelope(raw); if (!parsed.data) throw new Error('回执查询响应缺少回执。');
    return { ...parsed, data: receipt(isRecord(parsed.data) && isRecord(parsed.data.receipt) ? parsed.data.receipt : parsed.data) };
}
