import type { TransactionImportDraftSnapshot } from '../types/transactionImport';

const prefix = 'finance-os:transaction-import:draft:v1:';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const positiveInteger = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const validTime = (value: unknown) => typeof value === 'string' && !Number.isNaN(Date.parse(value));

export const transactionImportDraftKey = (userId: number) => `${prefix}${userId}`;

export function writeTransactionImportDraft(storage: Storage, snapshot: TransactionImportDraftSnapshot) {
    storage.setItem(transactionImportDraftKey(snapshot.userId), JSON.stringify(snapshot));
}

export function clearTransactionImportDraft(storage: Storage, userId: number) { storage.removeItem(transactionImportDraftKey(userId)); }

export function readTransactionImportDraft(storage: Storage, userId: number): TransactionImportDraftSnapshot | null {
    const raw = storage.getItem(transactionImportDraftKey(userId));
    if (!raw) return null;
    try {
        const value: unknown = JSON.parse(raw);
        if (!isRecord(value) || value.schemaVersion !== 1 || value.userId !== userId || !positiveInteger(userId)
            || (value.activeStep !== 'MAPPING' && value.activeStep !== 'PREVIEW_READY') || typeof value.sessionId !== 'string' || !uuid.test(value.sessionId)
            || !positiveInteger(value.revision) || !Array.isArray(value.detectedColumns) || !value.detectedColumns.every(column => typeof column === 'string')
            || !validTime(value.expiresAt) || !validTime(value.updatedAt) || typeof value.sessionStatus !== 'string'
            || typeof value.fileDigest !== 'string' || typeof value.optionsDigest !== 'string') return null;
        if (value.activeStep === 'PREVIEW_READY' && (typeof value.previewToken !== 'string' || !value.previewToken.trim())) return null;
        if (value.batchId !== null && (typeof value.batchId !== 'string' || !uuid.test(value.batchId))) return null;
        return value as unknown as TransactionImportDraftSnapshot;
    } catch { return null; }
}
