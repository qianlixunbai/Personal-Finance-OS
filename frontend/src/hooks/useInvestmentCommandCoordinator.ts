import { useCallback, useEffect, useRef, useState } from 'react';
import { postInvestmentCommand } from '../api/investmentCommand';
import { fetchInvestmentTransaction } from '../api/investment';
import type { InvestmentCommandDraft, InvestmentCommandReceipt, PendingInvestmentCommandV1 } from '../types/investmentCommand';
import { commandPath, createCommandBody, validateDraft } from '../utils/investmentCommandValidation';
import { authUserStorageKey, pendingStorageKey, scanPendingCommands } from '../utils/investmentCommandStorage';
import { continuationForPendingCommand, replacementRecoveryEvidenceMatches, runPendingGetUnderLock, runPendingPostUnderLock } from '../utils/investmentCommandRecovery';

export interface PendingPresentation { state: string; invalid: boolean; message: string; continuation?: string; }
export interface CoordinatorOptions {
    refresh: (record: PendingInvestmentCommandV1) => Promise<void>;
    reconcile: (record: PendingInvestmentCommandV1) => Promise<void>;
}

function readUserId() {
    const raw = localStorage.getItem(authUserStorageKey);
    return raw && /^\d+$/.test(raw) && Number(raw) > 0 ? Number(raw) : null;
}
function now() { return new Date().toISOString(); }

export function useInvestmentCommandCoordinator({ refresh, reconcile }: CoordinatorOptions) {
    const [pending, setPending] = useState<PendingPresentation | null>(null);
    const [receipt, setReceipt] = useState<InvestmentCommandReceipt | null>(null);
    const [receiptRefreshed, setReceiptRefreshed] = useState(false);
    const active = useRef<Promise<void> | null>(null);
    const scan = useCallback((startup = false) => {
        const userId = readUserId();
        if (!userId) { setPending(null); return; }
        const entries = scanPendingCommands(localStorage, userId);
        const first = entries[0];
        if (!first) { setPending(null); return; }
        if (!first.validation.ok) { setPending({ state: 'RECOVERY_RECORD_INVALID', invalid: true, message: first.validation.reason }); return; }
        const record = first.validation.record;
        if (startup && (record.state === 'SUBMITTING' || record.state === 'RECOVERING')) {
            setPending({ state: 'OUTCOME_UNKNOWN', invalid: false, message: '提交结果暂时无法确认。请恢复原提交，避免重复记账。' });
            return;
        }
        if (record.state === 'SUBMITTING') {
            setPending({ state: 'SUBMITTING', invalid: false, message: '另一个标签页正在提交投资记录，请等待结果。' });
            return;
        }
        if (record.state === 'RECOVERING') {
            setPending({ state: 'RECOVERING', invalid: false, message: '另一个标签页正在恢复原提交，请等待结果。' });
            return;
        }
        const continuation = continuationForPendingCommand(record).kind;
        setPending({ state: record.state, invalid: false, continuation, message: continuation === 'SUCCESS_REFRESH_GET' ? '写入已成功，当前数据尚未刷新。' : continuation === 'RECONCILE_GET' ? '当前投资状态已变化或请求发生冲突，请刷新后检查。' : record.state === 'AUTH_REQUIRED' ? '需要重新登录后由您继续恢复操作。' : '存在尚未完成的投资命令，新的投资写入已锁定。' });
    }, []);
    useEffect(() => { scan(true); const onStorage = () => scan(false); window.addEventListener('storage', onStorage); return () => window.removeEventListener('storage', onStorage); }, [scan]);
    useEffect(() => { const warn = (event: BeforeUnloadEvent) => { if (pending) event.preventDefault(); }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn); }, [pending]);

    const locked = useCallback(async <T,>(userId: number, operation: () => Promise<T>) => {
        if (!navigator.locks?.request) throw new Error('当前浏览器无法提供安全的跨标签提交保护，请使用受支持的新版浏览器。');
        return navigator.locks.request(`finance-os:investment-command-submit:v1:${userId}`, { mode: 'exclusive' }, operation);
    }, []);
    const tryLocked = useCallback(async <T,>(userId: number, operation: () => Promise<T>) => {
        if (!navigator.locks?.request) throw new Error('当前浏览器无法提供安全的跨标签提交保护，请使用受支持的新版浏览器。');
        return navigator.locks.request(`finance-os:investment-command-submit:v1:${userId}`, { mode: 'exclusive', ifAvailable: true }, lock => lock ? operation() : undefined);
    }, []);
    const write = useCallback((record: PendingInvestmentCommandV1) => {
        localStorage.setItem(pendingStorageKey(record.userId, record.idempotencyKey), JSON.stringify(record));
    }, []);
    const markGetUnauthorized = useCallback((record: PendingInvestmentCommandV1, resumeState: 'SUCCEEDED_AWAITING_REFRESH' | 'REJECTED_AWAITING_RECONCILE') => {
        const authRecord: PendingInvestmentCommandV1 = { ...record, state: 'AUTH_REQUIRED', resumeState, updatedAt: now() };
        write(authRecord);
        setPending({ state: authRecord.state, invalid: false, message: '需要重新登录后由您继续恢复操作。' });
    }, [write]);
    const send = useCallback(async (record: PendingInvestmentCommandV1, recovery: boolean) => {
        try {
            const response = await postInvestmentCommand(record.path, record.bodyJson, record.idempotencyKey, { onUnauthorized: () => {
                const authRecord = { ...record, state: 'AUTH_REQUIRED' as const, resumeState: recovery ? 'OUTCOME_UNKNOWN' as const : 'SUBMITTING' as const, updatedAt: now() };
                write(authRecord);
            } });
            const succeeded: PendingInvestmentCommandV1 = { ...record, state: 'SUCCEEDED_AWAITING_REFRESH', refresh: { assetId: response.assetId, logicalTransactionId: response.logicalTransactionId }, updatedAt: now() };
            write(succeeded);
            setReceipt({ ...response, commandType: record.commandType });
            setReceiptRefreshed(false);
            setPending({ state: succeeded.state, invalid: false, message: '写入已成功，正在刷新权威数据。' });
            try {
                await refresh(succeeded);
                localStorage.removeItem(pendingStorageKey(record.userId, record.idempotencyKey));
                setReceiptRefreshed(true);
                setPending(null);
            } catch (refreshError) {
                if ((refreshError as { response?: { status?: number } }).response?.status === 401) {
                    markGetUnauthorized(succeeded, 'SUCCEEDED_AWAITING_REFRESH');
                    return;
                }
                setPending({ state: succeeded.state, invalid: false, message: '写入已成功，当前数据尚未刷新。请稍后重新刷新，不要再次提交。' });
                return;
            }
        } catch (error) {
            const status = (error as { response?: { status?: number } }).response?.status;
            if (status === 401) { scan(); return; }
            if (!recovery && (status === 400 || status === 403 || status === 404)) {
                localStorage.removeItem(pendingStorageKey(record.userId, record.idempotencyKey)); setPending(null); throw error;
            }
            const rejected = !recovery && status === 409;
            const state: PendingInvestmentCommandV1['state'] = rejected ? 'REJECTED_AWAITING_RECONCILE' : 'OUTCOME_UNKNOWN';
            const unknown = { ...record, state, updatedAt: now() };
            try { write(unknown); setPending({ state: unknown.state, invalid: false, message: rejected ? '当前投资状态已变化或请求发生冲突，请刷新后检查。' : '提交结果暂时无法确认。请恢复原提交，避免重复记账。' }); } catch { setPending({ state: 'RECOVERY_RECORD_INVALID', invalid: true, message: '无法安全持久化恢复状态，投资写入已锁定。' }); }
            throw error;
        }
    }, [markGetUnauthorized, refresh, scan, write]);
    const submit = useCallback(async (draft: InvestmentCommandDraft) => {
        if (active.current) return;
        const validation = validateDraft(draft); if (validation) throw new Error(validation);
        const userId = readUserId(); if (!userId) throw new Error('无法确认当前登录用户，请重新登录。');
        const operation = (async () => {
            const record = await locked(userId, async () => {
                if (scanPendingCommands(localStorage, userId).length > 0) throw new Error('已有尚未完成的投资命令，请先处理恢复提示。');
                const idempotencyKey = crypto.randomUUID();
                const timestamp = now();
                const record: PendingInvestmentCommandV1 = { schemaVersion: 1, userId, state: 'SUBMITTING', commandType: draft.commandType, method: 'POST', path: commandPath(draft.commandType, draft), idempotencyKey, bodyJson: JSON.stringify(createCommandBody(draft)), originalTransactionType: draft.commandType === 'REPLACEMENT' ? draft.replacementTransactionType : undefined, replacementRequestType: draft.commandType === 'REPLACEMENT' ? draft.replacementRequestType : undefined, target: { assetId: draft.assetId, logicalTransactionId: draft.logicalTransactionId, accountId: draft.accountId, instrumentId: draft.instrumentId }, submittedAt: timestamp, updatedAt: timestamp };
                write(record);
                setPending({ state: 'SUBMITTING', invalid: false, message: '正在安全提交投资记录…' });
                return record;
            });
            await send(record, false);
        })();
        active.current = operation; try { await operation; } finally { active.current = null; }
    }, [locked, send, write]);
    const recover = useCallback(async () => {
        if (active.current) return;
        const userId = readUserId(); if (!userId) throw new Error('请先重新登录。');
        const operation = (async () => {
            const read = () => {
                const entry = scanPendingCommands(localStorage, userId)[0];
                if (!entry?.validation.ok) throw new Error('恢复记录无效，无法安全继续。');
                return entry.validation.record;
            };
            const current = read();
            const continuation = continuationForPendingCommand(current).kind;
            if (continuation === 'INITIAL_POST' || continuation === 'RECOVERY_POST' || current.state === 'RECOVERING') {
                const started = await runPendingPostUnderLock(userId, tryLocked, read, write, send, async record => {
                    if (record.commandType !== 'REPLACEMENT') return true;
                    const logicalTransactionId = record.target.logicalTransactionId;
                    if (!logicalTransactionId) return false;
                    try {
                        const original = await fetchInvestmentTransaction(logicalTransactionId);
                        return replacementRecoveryEvidenceMatches(record, original.transactionType);
                    } catch { return false; }
                });
                if (started === undefined) setPending({ state: 'RECOVERING', invalid: false, message: '另一个标签页正在恢复原提交，请等待结果。' });
                return;
            }
            if (continuation === 'SUCCESS_REFRESH_GET' || continuation === 'RECONCILE_GET') {
                try {
                    await runPendingGetUnderLock(userId, locked, read, write, async (record, kind) => {
                        await (kind === 'SUCCESS_REFRESH_GET' ? refresh(record) : reconcile(record));
                        localStorage.removeItem(pendingStorageKey(record.userId, record.idempotencyKey)); setPending(null);
                    });
                } catch (getError) {
                    const latest = read();
                    if ((getError as { response?: { status?: number } }).response?.status === 401) markGetUnauthorized(latest, continuation === 'SUCCESS_REFRESH_GET' ? 'SUCCEEDED_AWAITING_REFRESH' : 'REJECTED_AWAITING_RECONCILE');
                    else throw getError;
                }
                return;
            }
            throw new Error('当前状态不允许发送恢复请求。');
        })(); active.current = operation; try { await operation; } finally { active.current = null; }
    }, [locked, markGetUnauthorized, reconcile, refresh, send, tryLocked, write]);
    const exportEvidence = useCallback(() => {
        const userId = readUserId(); if (!userId) return;
        const entry = scanPendingCommands(localStorage, userId)[0]; if (!entry) return;
        const blob = new Blob([JSON.stringify({ storageKey: entry.storageKey, rawValue: entry.raw, exportedAt: now() }, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'investment-command-recovery-evidence.json'; link.click(); URL.revokeObjectURL(url);
    }, []);
    return { pending, receipt, receiptRefreshed, submit, recover, exportEvidence, scan, writeDisabled: Boolean(pending) };
}
