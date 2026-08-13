import type { PendingInvestmentCommandV1 } from '../types/investmentCommand';

const replacementTransactionTypes = new Set<NonNullable<PendingInvestmentCommandV1['originalTransactionType']>>(['BUY', 'SELL', 'DIVIDEND']);

export type PendingContinuation =
    | { kind: 'INITIAL_POST' }
    | { kind: 'RECOVERY_POST' }
    | { kind: 'SUCCESS_REFRESH_GET' }
    | { kind: 'RECONCILE_GET' }
    | { kind: 'NONE' };

/**
 * A recovery POST may only use a persisted replacement marker when the current
 * authoritative original-transaction read independently proves the same subtype.
 */
export function replacementRecoveryEvidenceMatches(record: PendingInvestmentCommandV1, authoritativeTransactionType: unknown) {
    if (record.commandType !== 'REPLACEMENT') return true;
    return replacementTransactionTypes.has(record.originalTransactionType as NonNullable<PendingInvestmentCommandV1['originalTransactionType']>)
        && replacementTransactionTypes.has(record.replacementRequestType as NonNullable<PendingInvestmentCommandV1['replacementRequestType']>)
        && replacementTransactionTypes.has(authoritativeTransactionType as NonNullable<PendingInvestmentCommandV1['originalTransactionType']>)
        && record.originalTransactionType === authoritativeTransactionType
        && record.replacementRequestType === authoritativeTransactionType;
}

export function continuationForPendingCommand(record: PendingInvestmentCommandV1): PendingContinuation {
    const state = record.state === 'AUTH_REQUIRED' ? record.resumeState : record.state;
    if (state === 'SUBMITTING') return { kind: 'INITIAL_POST' };
    if (state === 'OUTCOME_UNKNOWN') return { kind: 'RECOVERY_POST' };
    if (state === 'SUCCEEDED_AWAITING_REFRESH') return { kind: 'SUCCESS_REFRESH_GET' };
    if (state === 'REJECTED_AWAITING_RECONCILE') return { kind: 'RECONCILE_GET' };
    return { kind: 'NONE' };
}

export async function runPendingPostUnderLock(
    userId: number,
    locked: <T>(userId: number, operation: () => Promise<T>) => Promise<T | undefined>,
    read: () => PendingInvestmentCommandV1,
    write: (record: PendingInvestmentCommandV1) => void,
    send: (record: PendingInvestmentCommandV1, recovery: boolean) => Promise<void>,
    verifyRecoveryEvidence: (record: PendingInvestmentCommandV1) => Promise<boolean> = async () => true,
) {
    return locked(userId, async () => {
        const current = read();
        const continuation = continuationForPendingCommand(current).kind;
        const interruptedPost = current.state === 'SUBMITTING' || current.state === 'RECOVERING';
        const resumedReplacementPost = current.state === 'AUTH_REQUIRED'
            && continuation === 'INITIAL_POST'
            && current.commandType === 'REPLACEMENT';
        const recovery = interruptedPost || continuation === 'RECOVERY_POST';
        if (continuation !== 'INITIAL_POST' && !recovery) return false;
        if (recovery || resumedReplacementPost) {
            try {
                if (!await verifyRecoveryEvidence(current)) return false;
            } catch { return false; }
        }
        const record: PendingInvestmentCommandV1 = {
            ...current,
            state: recovery ? 'RECOVERING' : 'SUBMITTING',
            resumeState: undefined,
            updatedAt: new Date().toISOString(),
        };
        write(record);
        await send(record, recovery);
        return true;
    });
}

export async function runPendingGetUnderLock(
    userId: number,
    locked: <T>(userId: number, operation: () => Promise<T>) => Promise<T>,
    read: () => PendingInvestmentCommandV1,
    write: (record: PendingInvestmentCommandV1) => void,
    request: (record: PendingInvestmentCommandV1, kind: 'SUCCESS_REFRESH_GET' | 'RECONCILE_GET') => Promise<void>,
) {
    return locked(userId, async () => {
        const current = read();
        const continuation = continuationForPendingCommand(current).kind;
        if (continuation !== 'SUCCESS_REFRESH_GET' && continuation !== 'RECONCILE_GET') return false;
        const restored: PendingInvestmentCommandV1 = current.state === 'AUTH_REQUIRED'
            ? { ...current, state: current.resumeState as 'SUCCEEDED_AWAITING_REFRESH' | 'REJECTED_AWAITING_RECONCILE', resumeState: undefined, updatedAt: new Date().toISOString() }
            : current;
        if (restored !== current) write(restored);
        await request(restored, continuation);
        return true;
    });
}
