import type { PendingTransactionImportState, PendingTransactionImportV1, TransactionImportDraftSnapshot } from '../types/transactionImport';

const prefix = 'finance-os:transaction-import:draft:v1:';
const pendingPrefix = 'finance-os:transaction-import:pending:v1:';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const positiveInteger = (value: unknown) => typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const validTime = (value: unknown) => typeof value === 'string' && !Number.isNaN(Date.parse(value));

export const transactionImportDraftKey = (userId: number) => `${prefix}${userId}`;
export const pendingTransactionImportKey = (userId: number, key: string) => `${pendingPrefix}${userId}:${key}`;
export const pendingTransactionImportPrefix = (userId: number) => `${pendingPrefix}${userId}:`;

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

const pendingStates = new Set<PendingTransactionImportState>(['SUBMITTING', 'OUTCOME_UNKNOWN', 'RECOVERING_LOOKUP', 'RECOVERING_CONFIRM', 'COMMITTED_AWAITING_RECEIPT', 'AUTH_REQUIRED']);
const pendingResumeStates = new Set(['SUBMITTING', 'OUTCOME_UNKNOWN', 'RECOVERING_CONFIRM', 'COMMITTED_AWAITING_RECEIPT']);

function validConfirmBody(bodyJson: unknown) {
    if (typeof bodyJson !== 'string') return false;
    try {
        const body: unknown = JSON.parse(bodyJson);
        if (!isRecord(body) || Object.keys(body).length !== 2 || typeof body.previewToken !== 'string' || !body.previewToken.trim() || !Array.isArray(body.acknowledgedWarningIds)) return false;
        return body.acknowledgedWarningIds.every(value => typeof value === 'string' && value.trim());
    } catch { return false; }
}

function pendingValidation(value: unknown, userId: number, storageKey?: string): PendingTransactionImportV1 | null {
    if (!isRecord(value) || value.schemaVersion !== 1 || value.userId !== userId || !positiveInteger(userId)
        || typeof value.state !== 'string' || !pendingStates.has(value.state as PendingTransactionImportState)
        || value.method !== 'POST' || typeof value.importSessionId !== 'string' || !uuid.test(value.importSessionId)
        || typeof value.importBatchId !== 'string' || !uuid.test(value.importBatchId)
        || typeof value.idempotencyKey !== 'string' || !uuid.test(value.idempotencyKey)
        || value.path !== `/imports/transactions/${value.importSessionId}/confirm`
        || !validConfirmBody(value.bodyJson) || !validTime(value.submittedAt) || !validTime(value.updatedAt)) return null;
    const resume = value.resumeState;
    if (resume !== undefined && (typeof resume !== 'string' || !pendingResumeStates.has(resume))) return null;
    if (value.state === 'AUTH_REQUIRED' && resume === undefined) return null;
    if (value.state !== 'AUTH_REQUIRED' && resume !== undefined) return null;
    if (storageKey && storageKey !== pendingTransactionImportKey(userId, value.idempotencyKey)) return null;
    return value as unknown as PendingTransactionImportV1;
}

export function createPendingTransactionImport(input: { userId: number; importSessionId: string; importBatchId: string; idempotencyKey: string; bodyJson: string; now: string }): PendingTransactionImportV1 {
    const record: PendingTransactionImportV1 = { schemaVersion: 1, userId: input.userId, state: 'SUBMITTING', method: 'POST', path: `/imports/transactions/${input.importSessionId}/confirm`, importSessionId: input.importSessionId, importBatchId: input.importBatchId, idempotencyKey: input.idempotencyKey, bodyJson: input.bodyJson, submittedAt: input.now, updatedAt: input.now };
    if (!pendingValidation(record, input.userId)) throw new Error('无法安全创建确认恢复记录。');
    return record;
}

export function writePendingTransactionImport(storage: Storage, record: PendingTransactionImportV1) {
    if (!pendingValidation(record, record.userId)) throw new Error('确认恢复记录无效。');
    storage.setItem(pendingTransactionImportKey(record.userId, record.idempotencyKey), JSON.stringify(record));
}

export function readPendingTransactionImport(storage: Storage, userId: number, key: string): PendingTransactionImportV1 | null {
    const storageKey = pendingTransactionImportKey(userId, key); const raw = storage.getItem(storageKey); if (!raw) return null;
    try { return pendingValidation(JSON.parse(raw), userId, storageKey); } catch { return null; }
}

export function scanPendingTransactionImports(storage: Storage, userId: number) {
    const prefixForUser = pendingTransactionImportPrefix(userId);
    return Array.from({ length: storage.length }, (_, index) => storage.key(index)).filter((key): key is string => Boolean(key?.startsWith(prefixForUser))).map(storageKey => {
        const raw = storage.getItem(storageKey) ?? '';
        try { const record = pendingValidation(JSON.parse(raw), userId, storageKey); return record ? { storageKey, raw, validation: { ok: true as const, record } } : { storageKey, raw, validation: { ok: false as const, reason: '确认恢复记录不符合冻结 Contract。' } }; } catch { return { storageKey, raw, validation: { ok: false as const, reason: '确认恢复记录不是有效 JSON。' } }; }
    }).sort((left, right) => Number(!left.validation.ok) - Number(!right.validation.ok));
}

export function removePendingTransactionImport(storage: Storage, record: PendingTransactionImportV1) { storage.removeItem(pendingTransactionImportKey(record.userId, record.idempotencyKey)); }
