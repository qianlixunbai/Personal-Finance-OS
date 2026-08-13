import assert from 'node:assert/strict';
import test from 'node:test';

import {
    canOfferPositionAction,
    canOfferBuyWithAuthoritativeEligibility,
    createCommandBody,
    createCommandBodyJson,
    validateDraft,
    validateDecimalString,
} from '../src/utils/investmentCommandValidation.ts';
import {
    pendingStorageKey,
    scanPendingCommands,
    validatePendingCommand,
} from '../src/utils/investmentCommandStorage.ts';
import { continuationForPendingCommand, replacementRecoveryEvidenceMatches, runPendingGetUnderLock, runPendingPostUnderLock } from '../src/utils/investmentCommandRecovery.ts';
import { buildCommandConfirmation } from '../src/utils/investmentCommandConfirmation.ts';

class MemoryStorage implements Storage {
    private readonly values = new Map<string, string>();
    get length() { return this.values.size; }
    clear() { this.values.clear(); }
    getItem(key: string) { return this.values.get(key) ?? null; }
    key(index: number) { return [...this.values.keys()][index] ?? null; }
    removeItem(key: string) { this.values.delete(key); }
    setItem(key: string, value: string) { this.values.set(key, value); }
}

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

test('applies the frozen position action matrix', () => {
    assert.equal(canOfferPositionAction('OPEN', 'SELL'), true);
    assert.equal(canOfferPositionAction('CLOSED', 'SELL'), false);
    assert.equal(canOfferPositionAction('CLOSED', 'BUY'), true);
    assert.equal(canOfferPositionAction('CLOSED', 'DIVIDEND'), true);
});

test('fails closed for BUY when authoritative account or instrument eligibility is absent, inactive, or incompatible', () => {
    assert.equal(canOfferBuyWithAuthoritativeEligibility('ACTIVE', 'BROKERAGE', 'ACTIVE', 'ETF'), true);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('INACTIVE', 'BROKERAGE', 'ACTIVE', 'ETF'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('ACTIVE', 'BROKERAGE', 'INACTIVE', 'ETF'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility(undefined, 'BROKERAGE', 'ACTIVE', 'ETF'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('ACTIVE', undefined, 'ACTIVE', 'ETF'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('ACTIVE', 'CASH', 'ACTIVE', 'ETF'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('ACTIVE', 'BROKERAGE', 'ACTIVE', 'CRYPTO'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('UNKNOWN', 'BROKERAGE', 'ACTIVE', 'ETF'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('ACTIVE', 'UNKNOWN', 'ACTIVE', 'ETF'), false);
    assert.equal(canOfferBuyWithAuthoritativeEligibility('ACTIVE', 'BROKERAGE', 'UNKNOWN', 'ETF'), false);
});

test('accepts only an exact pending record for same-key recovery', () => {
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
});

test('persists replacement original subtype and rejects missing, unknown, incompatible, or conflicting subtype evidence', () => {
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
});

test('requires legal AUTH_REQUIRED source states and refresh identifiers', () => {
    const key = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
    const storageKey = pendingStorageKey(7, key);
    const base = {
        schemaVersion: 1, userId: 7, state: 'AUTH_REQUIRED', commandType: 'BUY', method: 'POST',
        path: '/investment/positions/44/buy', idempotencyKey: key,
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}', target: { assetId: 44 },
        submittedAt: '2026-08-12T00:00:00.000Z', updatedAt: '2026-08-12T00:00:00.000Z',
    };
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, resumeState: 'OUTCOME_UNKNOWN' }), 7).ok, true);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, resumeState: 'DRAFT' }), 7).ok, false);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, resumeState: 'SUCCEEDED_AWAITING_REFRESH' }), 7).ok, false);
});

test('accepts a standalone reversal with only its required reason', () => {
    assert.equal(validateDraft({ commandType: 'REVERSAL', logicalTransactionId: 8, quantity: '', unitPrice: '', grossAmount: '', feeAmount: '', taxAmount: '', externalReference: '', note: '', reason: '录入时金额有误' }), null);
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
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'SUBMITTING' }).kind, 'INITIAL_POST');
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'OUTCOME_UNKNOWN' }).kind, 'RECOVERY_POST');
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 9 } }).kind, 'SUCCESS_REFRESH_GET');
    assert.equal(continuationForPendingCommand({ ...base, resumeState: 'REJECTED_AWAITING_RECONCILE' }).kind, 'RECONCILE_GET');
});

test('does not classify an observed in-flight recovery as another recovery POST', () => {
    const record = pendingRecord({ state: 'RECOVERING' });
    assert.equal(continuationForPendingCommand(record as never).kind, 'NONE');
});

test('rejects contract-invalid recovery records and preserves their raw evidence', () => {
    const invalidBodies = [
        { label: 'scientific quantity', bodyJson: '{"quantity":"1e3","unitPrice":"2","feeAmount":"0","taxAmount":"0"}' },
        { label: 'scientific price', bodyJson: '{"quantity":"1","unitPrice":"1E2","feeAmount":"0","taxAmount":"0"}' },
        { label: 'negative quantity', bodyJson: '{"quantity":"-1","unitPrice":"2","feeAmount":"0","taxAmount":"0"}' },
        { label: 'zero quantity', bodyJson: '{"quantity":"0","unitPrice":"2","feeAmount":"0","taxAmount":"0"}' },
        { label: 'negative price', bodyJson: '{"quantity":"1","unitPrice":"-2","feeAmount":"0","taxAmount":"0"}' },
        { label: 'negative fee', bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"-0.01","taxAmount":"0"}' },
        { label: 'negative tax', bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"-0.01"}' },
        { label: 'unknown field', bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","extra":"x"}' },
    ];
    for (const example of invalidBodies) {
        const storage = new MemoryStorage();
        const record = pendingRecord({ bodyJson: example.bodyJson });
        const key = pendingStorageKey(7, record.idempotencyKey as string);
        const raw = JSON.stringify(record);
        storage.setItem(key, raw);
        assert.equal(validatePendingCommand(key, storage.getItem(key)!, 7).ok, false, example.label);
        assert.equal(storage.getItem(key), raw, `${example.label} raw evidence`);
    }
});

test('orders any invalid recovery evidence before valid pending commands', () => {
    const storage = new MemoryStorage();
    const valid = pendingRecord({ idempotencyKey: 'valid-key' });
    storage.setItem(pendingStorageKey(7, 'valid-key'), JSON.stringify(valid));
    storage.setItem(pendingStorageKey(7, 'broken-key'), '{broken');
    const entries = scanPendingCommands(storage, 7);
    assert.equal(entries.length, 2);
    assert.equal(entries[0].validation.ok, false);
});

test('validates DIVIDEND, correction reason, timestamps, target and refresh invariants', () => {
    const key = pendingStorageKey(7, pendingRecord().idempotencyKey as string);
    const cases = [
        pendingRecord({ commandType: 'DIVIDEND', path: '/investment/positions/44/dividends', bodyJson: '{"grossAmount":"1","feeAmount":"0.75","taxAmount":"0.50","externalReference":null,"note":null}' }),
        pendingRecord({ commandType: 'REVERSAL', path: '/investment/transactions/9/reversal', target: { assetId: 44, logicalTransactionId: 9 }, bodyJson: '{"reason":""}' }),
        pendingRecord({ commandType: 'REVERSAL', path: '/investment/transactions/9/reversal', target: { assetId: 44, logicalTransactionId: 9 }, bodyJson: '{"reason":" padded "}' }),
        pendingRecord({ submittedAt: 'not-a-time' }),
        pendingRecord({ updatedAt: '2026-08-12' }),
        pendingRecord({ target: { assetId: 44.5 }, path: '/investment/positions/44.5/buy' }),
        pendingRecord({ target: { assetId: -44 }, path: '/investment/positions/-44/buy' }),
        pendingRecord({ state: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 0, logicalTransactionId: 9 } }),
        pendingRecord({ unexpected: 'field' }),
    ];
    for (const [index, record] of cases.entries()) {
        assert.equal(validatePendingCommand(key, JSON.stringify(record), 7).ok, false, `invalid case ${index}`);
    }
});

test('requires exact response-derived refresh targets', () => {
    const base = pendingRecord({ state: 'SUCCEEDED_AWAITING_REFRESH', commandType: 'BUY', refresh: { assetId: 44, logicalTransactionId: 9 } });
    const storageKey = pendingStorageKey(7, base.idempotencyKey);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify(base), 7).ok, true);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, refresh: { assetId: 45, logicalTransactionId: 9 } }), 7).ok, false);
    assert.equal(validatePendingCommand(storageKey, JSON.stringify({ ...base, refresh: { assetId: 44, logicalTransactionId: 9, extra: true } }), 7).ok, false);
    const correction = pendingRecord({ state: 'SUCCEEDED_AWAITING_REFRESH', commandType: 'REVERSAL', path: '/investment/transactions/8/reversal', bodyJson: '{"reason":"更正"}', target: { assetId: 44, logicalTransactionId: 8 }, refresh: { assetId: 44, logicalTransactionId: 9 } });
    assert.equal(validatePendingCommand(pendingStorageKey(7, correction.idempotencyKey), JSON.stringify(correction), 7).ok, false);
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

test('blocks a replacement recovery POST when authoritative subtype evidence contradicts its persisted marker', async () => {
    let record = pendingRecord({
        commandType: 'REPLACEMENT', path: '/investment/transactions/12/replacement',
        target: { assetId: 44, logicalTransactionId: 12 },
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}',
        originalTransactionType: 'SELL',
    }) as never;
    let posts = 0;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => operation();
    const started = await runPendingPostUnderLock(7, locked, () => record, next => { record = next; }, async () => { posts += 1; }, async () => false);
    assert.equal(started, false);
    assert.equal(posts, 0);
    assert.equal((record as { state: string }).state, 'OUTCOME_UNKNOWN');
});

test('blocks an AUTH_REQUIRED replacement resume when authoritative subtype evidence contradicts its persisted marker', async () => {
    let record = pendingRecord({
        state: 'AUTH_REQUIRED', resumeState: 'SUBMITTING', commandType: 'REPLACEMENT', path: '/investment/transactions/12/replacement',
        target: { assetId: 44, logicalTransactionId: 12 },
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}',
        originalTransactionType: 'BUY', replacementRequestType: 'SELL',
    }) as never;
    let posts = 0;
    let verifications = 0;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => operation();
    const started = await runPendingPostUnderLock(
        7,
        locked,
        () => record,
        next => { record = next; },
        async () => { posts += 1; },
        async () => { verifications += 1; return false; },
    );
    assert.equal(started, false);
    assert.equal(verifications, 1);
    assert.equal(posts, 0);
    assert.equal((record as { state: string }).state, 'AUTH_REQUIRED');
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

test('allows exactly one AUTH_REQUIRED replacement resume after matching authoritative evidence', async () => {
    let record = pendingRecord({
        state: 'AUTH_REQUIRED', resumeState: 'SUBMITTING', commandType: 'REPLACEMENT', path: '/investment/transactions/12/replacement',
        target: { assetId: 44, logicalTransactionId: 12 },
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}',
        originalTransactionType: 'BUY', replacementRequestType: 'BUY',
    }) as never;
    let posts = 0;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => operation();
    const started = await runPendingPostUnderLock(7, locked, () => record, next => { record = next; }, async () => {
        posts += 1;
        record = { ...(record as object), state: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: 44, logicalTransactionId: 12 } } as never;
    }, async () => true);
    assert.equal(started, true);
    assert.equal(posts, 1);
    assert.equal((record as { state: string }).state, 'SUCCEEDED_AWAITING_REFRESH');
});

test('keeps a non-replacement AUTH_REQUIRED initial resume outside unknown-outcome recovery semantics', async () => {
    let record = pendingRecord({ state: 'AUTH_REQUIRED', resumeState: 'SUBMITTING', commandType: 'BUY' }) as never;
    let recoveryFlag: boolean | undefined;
    const locked = async <T,>(_userId: number, operation: () => Promise<T>) => operation();
    await runPendingPostUnderLock(7, locked, () => record, next => { record = next; }, async (_record, recovery) => { recoveryFlag = recovery; });
    assert.equal(recoveryFlag, false);
    assert.equal((record as { state: string }).state, 'SUBMITTING');
});

test('requires every replacement recovery subtype evidence source to agree', () => {
    const replacement = pendingRecord({
        commandType: 'REPLACEMENT', path: '/investment/transactions/12/replacement',
        target: { assetId: 44, logicalTransactionId: 12 },
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}',
        originalTransactionType: 'BUY',
        replacementRequestType: 'BUY',
    }) as never;
    for (const persisted of ['BUY', 'SELL', 'DIVIDEND'] as const) {
        for (const authoritative of ['BUY', 'SELL', 'DIVIDEND'] as const) {
            for (const request of ['BUY', 'SELL', 'DIVIDEND'] as const) {
                assert.equal(
                    replacementRecoveryEvidenceMatches({ ...replacement, originalTransactionType: persisted, replacementRequestType: request }, authoritative),
                    persisted === authoritative && authoritative === request,
                    `${authoritative} authoritative / ${persisted} persisted / ${request} request`,
                );
            }
        }
    }
    for (const invalid of [undefined, 'TRANSFER', 'OPENING_POSITION']) {
        assert.equal(replacementRecoveryEvidenceMatches(replacement, invalid), false, `unknown or missing authoritative subtype: ${String(invalid)}`);
        assert.equal(replacementRecoveryEvidenceMatches({ ...replacement, originalTransactionType: invalid }, 'BUY'), false, `unknown or missing persisted subtype: ${String(invalid)}`);
        assert.equal(replacementRecoveryEvidenceMatches({ ...replacement, replacementRequestType: invalid }, 'BUY'), false, `unknown or missing request subtype: ${String(invalid)}`);
    }
});

test('rejects a replacement recovery record without explicit request subtype evidence', () => {
    const key = '9c7fcbe8-c16b-4d61-a7f4-521a6bb6d091';
    const storageKey = pendingStorageKey(7, key);
    const replacement = pendingRecord({
        commandType: 'REPLACEMENT',
        path: '/investment/transactions/12/replacement',
        target: { assetId: 44, logicalTransactionId: 12 },
        bodyJson: '{"quantity":"1","unitPrice":"2","feeAmount":"0","taxAmount":"0","reason":"correct price"}',
        originalTransactionType: 'BUY',
    });
    assert.equal(validatePendingCommand(storageKey, JSON.stringify(replacement), 7).ok, false);
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

test('builds the frozen SELL confirmation from the read model', () => {
    const confirmation = buildCommandConfirmation(
        { commandType: 'SELL', assetId: 44, quantity: '2', unitPrice: '12.50', grossAmount: '', feeAmount: '0.20', taxAmount: '0.10', externalReference: '', note: '', reason: '' },
        { instrumentName: '沪深 300 ETF', instrumentSymbol: '510300', accountName: '投资账户', currentQuantity: '10' },
    );
    assert.deepEqual(confirmation.rows, [
        ['标的', '沪深 300 ETF（510300）'], ['账户', '投资账户'], ['当前持仓数量', '10'], ['本次卖出数量', '2'],
        ['单价', '12.50'], ['费用', '0.20'], ['税费', '0.10'],
    ]);
    assert.match(confirmation.notice, /后端计算/);
});

test('builds reversal original details and replacement before-after fields', () => {
    const original = { quantity: '10', unitPrice: '100', grossAmount: '1000', feeAmount: '1', taxAmount: '0', externalReference: 'REF-1', note: '原备注' };
    const context = { instrumentName: '测试标的', instrumentSymbol: 'TST', accountName: '投资账户', transactionType: 'BUY', effectiveTradeTime: '2026-08-12T01:02:03.000Z', original };
    const reversal = buildCommandConfirmation({ commandType: 'REVERSAL', logicalTransactionId: 9, quantity: '', unitPrice: '', grossAmount: '', feeAmount: '', taxAmount: '', externalReference: '', note: '', reason: '录入错误' }, undefined, context);
    assert.equal(reversal.rows.some(([label, value]) => label === '原交易类型' && value === 'BUY'), true);
    assert.equal(reversal.rows.some(([label]) => label === '原数量'), true);
    assert.equal(reversal.rows.some(([label]) => label === '生效交易时间'), true);
    assert.match(reversal.notice, /不会删除/);
    const replacement = buildCommandConfirmation({ commandType: 'REPLACEMENT', replacementTransactionType: 'BUY', replacementRequestType: 'BUY', logicalTransactionId: 9, quantity: '12', unitPrice: '101', grossAmount: '', feeAmount: '1', taxAmount: '0', externalReference: 'REF-2', note: '新备注', reason: '更正数值' }, undefined, context);
    assert.equal(replacement.rows.some(([label, value]) => label === '更正请求类型' && value === 'BUY'), true);
    assert.deepEqual(replacement.comparisons?.slice(0, 4), [
        ['数量', '10', '12'], ['单价', '100', '101'], ['费用', '1', '1'], ['税费', '0', '0'],
    ]);
});

test('keeps replacement subtype independent from editable quantity', () => {
    const trade = { commandType: 'REPLACEMENT' as const, replacementTransactionType: 'BUY' as const, replacementRequestType: 'BUY' as const, logicalTransactionId: 8, quantity: '', unitPrice: '2', grossAmount: '10', feeAmount: '0', taxAmount: '0', externalReference: '', note: '', reason: '更正' };
    assert.match(validateDraft(trade), /数量/);
    assert.deepEqual(createCommandBody(trade), { quantity: '', unitPrice: '2', feeAmount: '0', taxAmount: '0', externalReference: null, note: null, reason: '更正' });
    const dividend = { ...trade, replacementTransactionType: 'DIVIDEND' as const, replacementRequestType: 'DIVIDEND' as const, grossAmount: '10' };
    assert.equal(validateDraft(dividend), null);
    assert.deepEqual(createCommandBody(dividend), { grossAmount: '10', feeAmount: '0', taxAmount: '0', externalReference: null, note: null, reason: '更正' });
});
