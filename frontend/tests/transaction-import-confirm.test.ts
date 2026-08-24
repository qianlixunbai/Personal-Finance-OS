import assert from 'node:assert/strict';
import test from 'node:test';

import api from '../src/api/index.ts';
import { confirmTransactionImport, fetchTransactionImportReceipt } from '../src/api/transactionImport.ts';
import {
    createPendingTransactionImport,
    pendingTransactionImportKey,
    readPendingTransactionImport,
    scanPendingTransactionImports,
    writePendingTransactionImport,
} from '../src/utils/transactionImportStorage.ts';
import { decodeTransactionImportReceiptResponse } from '../src/utils/transactionImportTransport.ts';
import { confirmDomainAction, recoveryActionForReceiptLookup } from '../src/utils/transactionImportRecovery.ts';

class MemoryStorage implements Storage {
    private readonly values = new Map<string, string>();
    get length() { return this.values.size; }
    clear() { this.values.clear(); }
    getItem(key: string) { return this.values.get(key) ?? null; }
    key(index: number) { return [...this.values.keys()][index] ?? null; }
    removeItem(key: string) { this.values.delete(key); }
    setItem(key: string, value: string) { this.values.set(key, value); }
}

const sessionId = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
const batchId = '0a7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
const key = 'd81c6c70-7c5d-4d36-ae45-fdd8f2a26a90';
const bodyJson = '{"previewToken":"opaque-token","acknowledgedWarningIds":["warning-a","warning-b"]}';

test('creates a closed, same-user pending confirm intent that preserves exact body bytes', () => {
    const storage = new MemoryStorage();
    const record = createPendingTransactionImport({ userId: 7, importSessionId: sessionId, importBatchId: batchId, idempotencyKey: key, bodyJson, now: '2026-08-25T08:00:00.000Z' });
    writePendingTransactionImport(storage, record);

    assert.equal(pendingTransactionImportKey(7, key), `finance-os:transaction-import:pending:v1:7:${key}`);
    assert.equal(readPendingTransactionImport(storage, 7, key)?.bodyJson, bodyJson);
    assert.equal(scanPendingTransactionImports(storage, 7)[0]?.validation.ok, true);
    assert.equal(scanPendingTransactionImports(storage, 8).length, 0);

    storage.setItem(pendingTransactionImportKey(7, key), JSON.stringify({ ...record, bodyJson: '{"previewToken":"opaque-token","acknowledgedWarningIds":[],"rows":[]}' }));
    assert.equal(readPendingTransactionImport(storage, 7, key), null);
});

test('allows a fallback POST 401 record to preserve its exact recovery-post resume state', () => {
    const storage = new MemoryStorage();
    const pending = createPendingTransactionImport({ userId: 7, importSessionId: sessionId, importBatchId: batchId, idempotencyKey: key, bodyJson, now: '2026-08-25T08:00:00.000Z' });
    const recovering = { ...pending, state: 'RECOVERING_CONFIRM' as const };
    writePendingTransactionImport(storage, recovering);
    writePendingTransactionImport(storage, { ...recovering, state: 'AUTH_REQUIRED', resumeState: 'RECOVERING_CONFIRM' });

    const entry = scanPendingTransactionImports(storage, 7)[0];
    assert.equal(entry?.validation.ok, true);
    if (entry?.validation.ok) assert.equal(entry.validation.record.resumeState, 'RECOVERING_CONFIRM');
});

test('decodes receipt decimals from raw JSON without JavaScript Number precision loss', () => {
    const response = decodeTransactionImportReceiptResponse(`{"code":200,"message":"ok","data":{"receipt":{"importSessionId":"${sessionId}","importBatchId":"${batchId}","status":"CONFIRMED","sourceFileName":"demo.csv","fileDigest":"file","totalRows":1,"acceptedRows":1,"createdCount":1,"skippedCount":0,"warningCount":0,"transactions":[{"rowNumber":1,"transactionId":9}],"accountImpacts":[{"accountId":3,"rowCount":1,"balanceBefore":9007199254740993.01,"delta":-0.01,"balanceAfter":9007199254740993.00},{"accountId":4,"rowCount":1,"balanceBefore":999999999999999999.999999,"delta":-123456789012345678.123456,"balanceAfter":"0.1"}],"confirmedAt":"2026-08-25T08:00:00Z","contractVersion":"v3","resultDigest":"digest"},"idempotentReplay":false}}`);

    assert.equal(response.data?.receipt.accountImpacts[0]?.balanceBefore, '9007199254740993.01');
    assert.equal(response.data?.receipt.accountImpacts[0]?.delta, '-0.01');
    assert.equal(response.data?.receipt.accountImpacts[0]?.balanceAfter, '9007199254740993.00');
    assert.equal(response.data?.receipt.accountImpacts[1]?.balanceBefore, '999999999999999999.999999');
    assert.equal(response.data?.receipt.accountImpacts[1]?.delta, '-123456789012345678.123456');
    assert.equal(response.data?.receipt.accountImpacts[1]?.balanceAfter, '0.1');
});

test('preserves an HTTP 200 Confirm fact when the receipt body cannot be decoded', async () => {
    const post = api.post; const get = api.get;
    const calls: Array<{ method: string; path: string; config?: Record<string, unknown> }> = [];
    api.post = async (path, body, config) => { calls.push({ method: 'POST', path, config: config as Record<string, unknown> }); return { status: 200, data: '{"code":200,"message":"ok","data":{"receipt":null}}' }; };
    api.get = async (path, config) => { calls.push({ method: 'GET', path, config: config as Record<string, unknown> }); return { data: '{"code":404,"message":"missing"}' }; };
    try {
        const result = await confirmTransactionImport(sessionId, bodyJson, key);
        assert.equal(result.status, 200);
        assert.equal(result.receipt, null);
        await assert.rejects(fetchTransactionImportReceipt(batchId));
    } finally { api.post = post; api.get = get; }
    assert.deepEqual(calls.map(call => [call.method, call.path]), [
        ['POST', `/imports/transactions/${sessionId}/confirm`],
        ['GET', `/imports/transactions/batches/${batchId}`],
    ]);
    for (const call of calls) {
        assert.equal(call.config?.responseType, 'text');
        const transform = call.config?.transformResponse as Array<(value: string) => string> | undefined;
        assert.ok(transform?.[0]);
        assert.equal(transform[0]('{"n":1}'), '{"n":1}');
    }
});

test('persists the caller-provided 401 recovery callback even when Axios rejects before the global redirect completes', async () => {
    const post = api.post; let called = 0;
    api.post = async () => { throw { response: { status: 401 } }; };
    try {
        await assert.rejects(confirmTransactionImport(sessionId, bodyJson, key, () => { called += 1; }));
    } finally { api.post = post; }
    assert.equal(called, 1);
});

test('uses GET first and only retries the frozen POST after a pending receipt lookup returns 404', () => {
    assert.equal(recoveryActionForReceiptLookup(200), 'RECEIPT_READY');
    assert.equal(recoveryActionForReceiptLookup(404), 'RETRY_SAME_CONFIRM');
    assert.equal(recoveryActionForReceiptLookup(401), 'AUTH_REQUIRED');
    assert.equal(recoveryActionForReceiptLookup(503), 'RETAIN_UNKNOWN');
});

test('maps every real Confirm and Receipt domain code to a fail-closed recovery action', () => {
    const cases = [
        ['IMPORT_CONFIRM_REQUEST_INVALID', 'RETURN_TO_PREVIEW'],
        ['IMPORT_SESSION_NOT_FOUND', 'RESTART_IMPORT'],
        ['IMPORT_PREVIEW_EXPIRED', 'TERMINAL_EXPIRED'],
        ['IMPORT_SESSION_CANCELLED', 'TERMINAL_CANCELLED'],
        ['IMPORT_PREVIEW_STALE', 'REPREVIEW'],
        ['IMPORT_PREVIEW_UNAVAILABLE', 'REPREVIEW'],
        ['IMPORT_DUPLICATE_EVIDENCE_CHANGED', 'REPREVIEW'],
        ['IMPORT_WARNING_ACK_REQUIRED', 'RETURN_TO_WARNING_REVIEW'],
        ['IMPORT_EXACT_DUPLICATE', 'BLOCK_EXACT_DUPLICATE'],
        ['IMPORT_BATCH_ALREADY_CONFIRMED', 'RECONCILE_RECEIPT'],
        ['IMPORT_IDEMPOTENCY_CONFLICT', 'MANUAL_RESOLUTION'],
        ['IMPORT_LOCK_CONFLICT', 'RECONCILE_RECEIPT'],
        ['IMPORT_CONFIRM_INCONSISTENT', 'INCONSISTENT'],
        ['IMPORT_BATCH_NOT_FOUND', 'RETRY_SAME_CONFIRM'],
    ] as const;

    for (const [errorCode, initialAction] of cases) {
        assert.equal(confirmDomainAction(errorCode, false), initialAction, errorCode);
        assert.equal(confirmDomainAction(errorCode, true), errorCode === 'IMPORT_BATCH_NOT_FOUND' ? 'RETRY_SAME_CONFIRM' : 'RETAIN_UNKNOWN', `${errorCode} recovery`);
    }
});
