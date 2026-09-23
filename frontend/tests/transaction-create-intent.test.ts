import assert from 'node:assert/strict';
import test from 'node:test';
import {
    canonicalSignature,
    newTransactionIdempotencyKey,
    nextTransactionCreateIntent,
} from '../src/utils/transactionCreateIntent.ts';

const payload = { accountId: 7, categoryId: 8, type: 'EXPENSE', amount: 25, currency: 'CNY', description: 'lunch', transactedAt: '2026-09-23T12:00:00' };

function sequentialKeys() {
    let counter = 0;
    return () => `key-${++counter}`;
}

test('first submit allocates a fresh key and records the intent signature', () => {
    const intent = nextTransactionCreateIntent(null, payload, sequentialKeys());

    assert.equal(intent.key, 'key-1');
    assert.equal(intent.signature, canonicalSignature(payload));
});

test('repeating the identical submit reuses the key so the backend replays instead of duplicating', () => {
    const generateKey = sequentialKeys();
    const first = nextTransactionCreateIntent(null, payload, generateKey);
    const retry = nextTransactionCreateIntent(first, { ...payload }, generateKey);

    assert.equal(retry.key, first.key);
    assert.equal(generateKey(), 'key-2', 'only one key should have been generated');
});

test('edited content gets a new key so a corrected resubmit is never read as the same request', () => {
    const generateKey = sequentialKeys();
    const first = nextTransactionCreateIntent(null, payload, generateKey);
    const corrected = nextTransactionCreateIntent(first, { ...payload, description: 'dinner' }, generateKey);

    assert.notEqual(corrected.key, first.key);
    assert.equal(corrected.key, 'key-2');
});

test('reverting an edit does not resurrect the stale key', () => {
    const generateKey = sequentialKeys();
    const first = nextTransactionCreateIntent(null, payload, generateKey);
    const corrected = nextTransactionCreateIntent(first, { ...payload, amount: 30 }, generateKey);
    const reverted = nextTransactionCreateIntent(corrected, { ...payload }, generateKey);

    assert.equal(reverted.key, 'key-3');
    assert.notEqual(reverted.key, first.key);
});

test('payload key order cannot silently break replay', () => {
    const reordered = { transactedAt: payload.transactedAt, description: payload.description, currency: payload.currency, amount: payload.amount, type: payload.type, categoryId: payload.categoryId, accountId: payload.accountId };

    assert.equal(canonicalSignature(payload), canonicalSignature(reordered));

    const generateKey = sequentialKeys();
    const first = nextTransactionCreateIntent(null, payload, generateKey);
    const retry = nextTransactionCreateIntent(first, reordered, generateKey);

    assert.equal(retry.key, first.key);
});

test('absent and null values produce the same signature', () => {
    assert.equal(canonicalSignature({ description: null }), canonicalSignature({ description: undefined }));
    assert.notEqual(canonicalSignature({ description: null }), canonicalSignature({ description: '' }));
});

test('generated keys are non-empty and distinct', () => {
    const first = newTransactionIdempotencyKey();
    const second = newTransactionIdempotencyKey();

    assert.equal(typeof first, 'string');
    assert.ok(first.length > 0 && first.length <= 100, 'key must fit the backend 1..100 character contract');
    assert.notEqual(first, second);
});
