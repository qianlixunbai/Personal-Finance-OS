import assert from 'node:assert/strict';
import test from 'node:test';

import {
    createCommandBodyJson,
    validateDecimalString,
} from '../src/utils/investmentCommandValidation.ts';
import { pendingStorageKey, validatePendingCommand } from '../src/utils/investmentCommandStorage.ts';
import { continuationForPendingCommand, replacementRecoveryEvidenceMatches, runPendingGetUnderLock, runPendingPostUnderLock } from '../src/utils/investmentCommandRecovery.ts';

function pendingRecord(overrides: Record<string, unknown> = {}) {
    const key = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
    return {
        schemaVersion: 1, userId: 7, state: 'OUTCOME_UNKNOWN', commandType: 'BUY', method: 'POST',
        path: '/investment/positions/44/buy', idempotencyKey: key,
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}',
        target: { assetId: 44 }, submittedAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z',
        ...overrides,
    };
}

test('keeps financial command values as fixed-point strings', () => {
    for (const value of ['0', '0.00', '-0.01', '1.00000000', '12345678901234567890.12']) {
        assert.equal(validateDecimalString(value, 8), null);
    }
    for (const value of ['1e3', '1E-2', 'NaN', 'Infinity', '1.000000000']) {
        assert.notEqual(validateDecimalString(value, 8), null);
    }
    const body = createCommandBodyJson({ quantity: '12345678901234567890.12', unitPrice: '1.00000000', feeAmount: '0.00', taxAmount: '0' });
    assert.equal(body, '{"quantity":"12345678901234567890.12","unitPrice":"1.00000000","feeAmount":"0.00","taxAmount":"0"}');
});

test('validates the exact pending command and its response-derived refresh target', () => {
    const key = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
    const storageKey = pendingStorageKey(7, key);
    const raw = JSON.stringify({
        schemaVersion: 1, userId: 7, state: 'OUTCOME_UNKNOWN', commandType: 'BUY', method: 'POST',
        path: '/investment/positions/44/buy', idempotencyKey: key,
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}',
        target: { assetId: 44 }, submittedAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z',
    });
    const result = validatePendingCommand(storageKey, raw, 7);
    assert.equal(result.ok, true);
    if (result.ok) assert.equal(result.record.bodyJson, '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}');
    assert.equal(validatePendingCommand(storageKey, raw.replace('/buy', '/sell'), 7).ok, false);
    assert.equal(validatePendingCommand(storageKey, raw, 8).ok, false);
    const numericBody = JSON.parse(raw) as { bodyJson: string };
    numericBody.bodyJson = '{"quantity":1,"unitPrice":"2","feeAmount":"0","taxAmount":"0"}';
    assert.equal(validatePendingCommand(storageKey, JSON.stringify(numericBody), 7).ok, false);
    const missingField = JSON.parse(raw) as { bodyJson: string };
    missingField.bodyJson = '{"quantity":"1","unitPrice":"2","feeAmount":"0","unexpected":"0"}';
    assert.equal(validatePendingCommand(storageKey, JSON.stringify(missingField), 7).ok, false);
    const completed = { ...JSON.parse(raw), state: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 9 } };
    assert.equal(validatePendingCommand(storageKey, JSON.stringify(completed), 7).ok, true);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...completed, refresh: { assetId: 45, logicalTransactionId: 9 } }), 7).ok, false);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...completed, refresh: { assetId: 44, logicalTransactionId: 9, extra: true } }), 7).ok, false);
    const correction = {
        ...completed, commandType: 'REVERSAL', path: '/investment/transactions/8/reversal',
        bodyJson: '{"reason":"更正"}', target: { assetId: 44, logicalTransactionId: 8 },
        refresh: { assetId: 44, logicalTransactionId: 9 },
    };
    assert.equal(validatePendingCommand(storageKey, JSON.stringify(correction), 7).ok, false);
});

test('persists replacement subtype and requires every recovery evidence source to agree', () => {
    const key = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
    const storageKey = pendingStorageKey(7, key);
    const replacement = pendingRecord({
        commandType: 'REPLACEMENT',
        path: '/investment/transactions/12/replacement',
        target: { assetId: 44, logicalTransactionId: 12 },
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}',
        originalTransactionType: 'BUY',
        replacementRequestType: 'BUY',
    });
    assert.equal(validatePendingCommand(storageKey, JSON.stringify(replacement), 7).ok, true, 'BUY marker survives reload as BUY evidence');
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...replacement, originalTransactionType: undefined }), 7).ok, false, 'missing persisted subtype fails closed');
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...replacement, originalTransactionType: 'TRANSFER' }), 7).ok, false, 'unknown subtype fails closed');
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...replacement, originalTransactionType: 'DIVIDEND' }), 7).ok, false, 'dividend marker cannot recover a trade replacement body');
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...replacement, originalTransactionType: 'SELL', replacementRequestType: 'SELL' }), 7).ok, true, 'SELL marker accepts only trade-shaped SELL replacement');
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...replacement, originalTransactionType: 'SELL', replacementRequestType: 'SELL', bodyJson: '{"grossAmount":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}' }), 7).ok, false, 'persisted SELL subtype conflicts with dividend-shaped body');
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...replacement, replacementRequestType: undefined }), 7).ok, false, 'missing request subtype fails closed');
    assert.equal(replacementRecoveryEvidenceMatches(replacement as never, 'BUY'), true);
    assert.equal(replacementRecoveryEvidenceMatches(replacement as never, 'SELL'), false);
    assert.equal(replacementRecoveryEvidenceMatches({ ...replacement, replacementRequestType: 'SELL' } as never, 'BUY'), false);
});

test('keeps first-submit 409 reconcile separate from unknown recovery', () => {
    const base = {
        schemaVersion: 1 as const, userId: 7, commandType: 'BUY' as const, method: 'POST' as const,
        path: '/investment/positions/44/buy', idempotencyKey: 'same-key',
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}',
        target: { assetId: 44 }, submittedAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z',
    };
    assert.equal(continuationForPendingCommand({ ...base, state: 'REJECTED_AWAITING_RECONCILE' }).kind, 'RECONCILE_GET');
    assert.equal(continuationForPendingCommand({ ...base, state: 'OUTCOME_UNKNOWN' }).kind, 'RECOVERY_POST');
});

test('preserves all AUTH_REQUIRED source semantics after reauthentication', () => {
    const base = {
        schemaVersion: 1 as const, userId: 7, state: 'AUTH_REQUIRED' as const, commandType: 'BUY' as const, method: 'POST' as const,
        path: '/investment/positions/44/buy', idempotencyKey: 'same-key',
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}',
        target: { assetId: 44 }, submittedAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z',
    };
    const storageKey = pendingStorageKey(7, base.idempotencyKey);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, resumeState: 'OUTCOME_UNKNOWN' }), 7).ok, true);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, resumeState: 'DRAFT' }), 7).ok, false);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, resumeState: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 9 } }), 7).ok, true);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, resumeState: 'SUCCEEDED_AWAITING_REFRESH' }), 7).ok, false);
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'SUBMITTING' }).kind, 'INITIAL_POST');
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'OUTCOME_UNKNOWN' }).kind, 'RECOVERY_POST');
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 9 } }).kind, 'SUCCESS_REFRESH_GET');
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'REJECTED_AWAITING_RECONCILE' }).kind, 'RECONCILE_GET');
});

test('keeps at most one same-record recovery POST in flight across tabs', async () => {
    let record = pendingRecord() as never;
    let releaseLock = () => {};
    let busy = false;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => {
        if (busy) return undefined;
        busy = true;
        try { return await operation(); } finally { busy = false; }
    };
    let postCount = 0; let concurrent = 0; let maxConcurrent = 0;
    const held = new Promise<void>(resolve => { releaseLock = resolve; });
    const sendA = async () => {
        postCount += 1; concurrent += 1; maxConcurrent = Math.max(maxConcurrent, concurrent);
        await held;
        concurrent -= 1;
        record = { ...(record as object), state: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 9 } } as never;
    };
    const sendB = async () => { postCount += 1; concurrent += 1; maxConcurrent = Math.max(maxConcurrent, concurrent); concurrent -= 1; };
    const write = (next: never) => { record = next; };
    const tabA = runPendingPostUnderLock(7, locked, () => record, write, sendA);
    await new Promise(resolve => setTimeout(resolve, 0));
    const tabB = runPendingPostUnderLock(7, locked, () => record, write, sendB);
    await new Promise(resolve => setTimeout(resolve, 0));
    assert.equal(postCount, 1);
    assert.equal(maxConcurrent, 1);
    releaseLock();
    await Promise.all([tabA, tabB]);
    assert.equal(postCount, 1);
    assert.equal(maxConcurrent, 1);
});

test('allows a later tab to recover only after the locked attempt becomes unknown', async () => {
    let record = pendingRecord() as never;
    let busy = false;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => {
        if (busy) return undefined;
        busy = true;
        try { return await operation(); } finally { busy = false; }
    };
    let posts = 0;
    const write = (next: never) => { record = next; };
    await runPendingPostUnderLock(7, locked, () => record, write, async () => {
        posts += 1;
        record = { ...(record as object), state: 'OUTCOME_UNKNOWN' } as never;
    });
    assert.equal(posts, 1);
    await runPendingPostUnderLock(7, locked, () => record, write, async () => {
        posts += 1;
        record = { ...(record as object), state: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 9 } } as never;
    });
    assert.equal(posts, 2);
    assert.equal((record as { state: string }).state, 'SUCCEEDED_AWAITING_REFRESH');
});

test('treats an interrupted persisted SUBMITTING record as recovery semantics', async () => {
    let record = pendingRecord({ state: 'SUBMITTING' }) as never;
    let recoveryFlag: boolean | undefined;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => operation();
    await runPendingPostUnderLock(7, locked, () => record, next => { record = next; }, async (_record, recovery) => { recoveryFlag = recovery; });
    assert.equal(recoveryFlag, true);
    assert.equal((record as { state: string }).state, 'RECOVERING');
});

test('fails closed for missing, unknown, or unreadable authoritative evidence during an AUTH_REQUIRED replacement resume', async () => {
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => operation();
    for (const verification of [async () => false, async () => { throw new Error('authoritative read failed'); }]) {
        let record = pendingRecord({
            state: 'AUTH_REQUIRED', resumeState: 'SUBMITTING', commandType: 'REPLACEMENT', path: '/investment/transactions/12/replacement',
            target: { assetId: 44, logicalTransactionId: 12 },
            bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}',
            originalTransactionType: 'BUY', replacementRequestType: 'BUY',
        }) as never;
        let posts = 0;
        const started = await runPendingPostUnderLock(7, locked, () => record, next => { record = next; }, async () => { posts += 1; }, verification);
        assert.equal(started, false);
        assert.equal(posts, 0);
        assert.equal((record as { state: string }).state, 'AUTH_REQUIRED');
    }
});

test('atomically restores an AUTH_REQUIRED GET source under the request lock', async () => {
    let record = pendingRecord({ state: 'AUTH_REQUIRED', resumeState: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 9 } }) as never;
    let stateObservedByGet = '';
    let lockHeld = false;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => {
        lockHeld = true;
        try { return await operation(); } finally { lockHeld = false; }
    };
    await runPendingGetUnderLock(7, locked, () => record, next => { record = next; }, async current => {
        assert.equal(lockHeld, true);
        stateObservedByGet = current.state;
    });
    assert.equal(stateObservedByGet, 'SUCCEEDED_AWAITING_REFRESH');
    assert.equal((record as { resumeState?: string }).resumeState, undefined);
});
