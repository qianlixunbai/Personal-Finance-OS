export interface TransactionCreateIntent { key: string; signature: string; }

/**
 * One Idempotency-Key per user intent, not per request.
 *
 * A repeated submit of byte-identical content (double click, or a retry after a lost
 * response) reuses the key so the backend replays the authoritative first result instead
 * of creating a second money fact. Edited content gets a fresh key, so fixing a typo and
 * resubmitting is never misread as "same key, different request".
 */
export function nextTransactionCreateIntent(
    current: TransactionCreateIntent | null,
    payload: Record<string, unknown>,
    generateKey: () => string,
): TransactionCreateIntent {
    const signature = canonicalSignature(payload);
    if (current && current.signature === signature) return current;
    return { key: generateKey(), signature };
}

/** Order-independent, undefined-normalised signature so payload key order cannot silently break replay. */
export function canonicalSignature(payload: Record<string, unknown>): string {
    return JSON.stringify(Object.keys(payload).sort().map(key => [key, payload[key] ?? null]));
}

/** crypto.randomUUID() only exists in secure contexts; keep a fallback for plain-HTTP deployments. */
export function newTransactionIdempotencyKey(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    return `tx-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
