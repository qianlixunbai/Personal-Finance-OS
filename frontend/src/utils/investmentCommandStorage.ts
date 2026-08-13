import type { InvestmentCommandType, PendingCommandState, PendingInvestmentCommandV1, ResumeState } from '../types/investmentCommand';
import { compareDecimalStrings, validateDecimalString } from './investmentCommandValidation.ts';

const prefix = 'finance-os:investment-command:pending:v1:';
const states = new Set<PendingCommandState>(['SUBMITTING', 'OUTCOME_UNKNOWN', 'RECOVERING', 'AUTH_REQUIRED', 'SUCCEEDED_AWAITING_REFRESH', 'REJECTED_AWAITING_RECONCILE']);
const types = new Set<InvestmentCommandType>(['FIRST_BUY', 'BUY', 'SELL', 'DIVIDEND', 'REVERSAL', 'REPLACEMENT']);
const resumes = new Set<ResumeState>(['SUBMITTING', 'OUTCOME_UNKNOWN', 'SUCCEEDED_AWAITING_REFRESH', 'REJECTED_AWAITING_RECONCILE']);

export const authUserStorageKey = 'finance-os:auth-user-id:v1';
export const pendingStorageKey = (userId: number, key: string) => `${prefix}${userId}:${key}`;
export const pendingStoragePrefix = (userId: number) => `${prefix}${userId}:`;

function object(value: unknown): value is Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function positiveInteger(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0; }
function exactFields(body: Record<string, unknown>, required: string[], optional: string[] = []) {
    const fields = Object.keys(body);
    return required.every(field => Object.hasOwn(body, field)) && fields.every(field => required.includes(field) || optional.includes(field));
}
function decimalField(body: Record<string, unknown>, field: string, scale: number, allowZero: boolean) {
    const value = body[field];
    if (typeof value !== 'string' || validateDecimalString(value, scale)) return false;
    const comparison = compareDecimalStrings(value, '0');
    return allowZero ? comparison >= 0 : comparison > 0;
}
function optionalText(body: Record<string, unknown>, field: string, maxLength: number) {
    const value = body[field];
    return value === undefined || value === null || typeof value === 'string' && value === value.trim() && value.length >= 1 && value.length <= maxLength;
}
function reason(body: Record<string, unknown>) {
    const value = body.reason;
    return typeof value === 'string' && value === value.trim() && value.length >= 1 && value.length <= 500;
}
function tradeBody(body: Record<string, unknown>, firstBuy: boolean) {
    const required = firstBuy ? ['accountId', 'instrumentId', 'quantity', 'unitPrice', 'feeAmount', 'taxAmount'] : ['quantity', 'unitPrice', 'feeAmount', 'taxAmount'];
    return exactFields(body, required)
        && (!firstBuy || positiveInteger(body.accountId) && positiveInteger(body.instrumentId))
        && decimalField(body, 'quantity', 8, false)
        && decimalField(body, 'unitPrice', 8, false)
        && decimalField(body, 'feeAmount', 2, true)
        && decimalField(body, 'taxAmount', 2, true);
}
function addDecimalStrings(left: string, right: string) {
    const scale = Math.max((left.split('.')[1] ?? '').length, (right.split('.')[1] ?? '').length);
    const toUnits = (value: string) => BigInt(value.replace('.', '') + '0'.repeat(scale - (value.split('.')[1] ?? '').length));
    const units = toUnits(left) + toUnits(right);
    if (scale === 0) return units.toString();
    const digits = units.toString().padStart(scale + 1, '0');
    return `${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
}
function dividendBody(body: Record<string, unknown>, replacement: boolean) {
    const required = replacement ? ['grossAmount', 'feeAmount', 'taxAmount', 'reason'] : ['grossAmount', 'feeAmount', 'taxAmount'];
    if (!exactFields(body, required, ['externalReference', 'note'])
        || !decimalField(body, 'grossAmount', 2, false)
        || !decimalField(body, 'feeAmount', 2, true)
        || !decimalField(body, 'taxAmount', 2, true)
        || !optionalText(body, 'externalReference', 100)
        || !optionalText(body, 'note', 500)
        || replacement && !reason(body)) return false;
    return compareDecimalStrings(addDecimalStrings(body.feeAmount as string, body.taxAmount as string), body.grossAmount as string) <= 0;
}
function replacementTradeBody(body: Record<string, unknown>) {
    return exactFields(body, ['quantity', 'unitPrice', 'feeAmount', 'taxAmount', 'reason'], ['externalReference', 'note'])
        && decimalField(body, 'quantity', 8, false)
        && decimalField(body, 'unitPrice', 8, false)
        && decimalField(body, 'feeAmount', 2, true)
        && decimalField(body, 'taxAmount', 2, true)
        && optionalText(body, 'externalReference', 100)
        && optionalText(body, 'note', 500)
        && reason(body);
}
function bodyMatches(type: InvestmentCommandType, bodyJson: string, replacementRequestType?: PendingInvestmentCommandV1['replacementRequestType']) {
    try {
        const body: unknown = JSON.parse(bodyJson);
        if (!object(body)) return false;
        if (type === 'FIRST_BUY') return tradeBody(body, true);
        if (type === 'BUY' || type === 'SELL') return tradeBody(body, false);
        if (type === 'DIVIDEND') return dividendBody(body, false);
        if (type === 'REVERSAL') return exactFields(body, ['reason']) && reason(body);
        const hasTradeFields = Object.hasOwn(body, 'quantity') || Object.hasOwn(body, 'unitPrice');
        const hasDividendField = Object.hasOwn(body, 'grossAmount');
        if (replacementRequestType !== 'BUY' && replacementRequestType !== 'SELL' && replacementRequestType !== 'DIVIDEND') return false;
        return replacementRequestType === 'DIVIDEND'
            ? !hasTradeFields && hasDividendField && dividendBody(body, true)
            : hasTradeFields && !hasDividendField && replacementTradeBody(body);
    } catch { return false; }
}
function pathMatches(record: PendingInvestmentCommandV1) {
    const { commandType, path, target } = record;
    const targetFields = Object.keys(target);
    if (commandType === 'FIRST_BUY') {
        if (targetFields.length !== 2 || !targetFields.includes('accountId') || !targetFields.includes('instrumentId') || path !== '/investment/positions' || !positiveInteger(target.accountId) || !positiveInteger(target.instrumentId)) return false;
        const body = JSON.parse(record.bodyJson) as Record<string, unknown>;
        return body.accountId === target.accountId && body.instrumentId === target.instrumentId;
    }
    if (commandType === 'BUY' || commandType === 'SELL' || commandType === 'DIVIDEND') return targetFields.length === 1 && targetFields[0] === 'assetId' && path === `/investment/positions/${target.assetId}/${commandType === 'BUY' ? 'buy' : commandType === 'SELL' ? 'sell' : 'dividends'}` && positiveInteger(target.assetId);
    return targetFields.length === 2 && targetFields.includes('logicalTransactionId') && targetFields.includes('assetId') && path === `/investment/transactions/${target.logicalTransactionId}/${commandType === 'REVERSAL' ? 'reversal' : 'replacement'}` && positiveInteger(target.logicalTransactionId) && positiveInteger(target.assetId);
}
function refreshMatches(record: PendingInvestmentCommandV1) {
    if (!record.refresh || !object(record.refresh) || !exactFields(record.refresh, ['assetId', 'logicalTransactionId'])
        || !positiveInteger(record.refresh.assetId) || !positiveInteger(record.refresh.logicalTransactionId)) return false;
    if (record.commandType === 'FIRST_BUY') return true;
    if (record.refresh.assetId !== record.target.assetId) return false;
    return record.commandType !== 'REVERSAL' && record.commandType !== 'REPLACEMENT'
        || record.refresh.logicalTransactionId === record.target.logicalTransactionId;
}

export type PendingValidation = { ok: true; record: PendingInvestmentCommandV1 } | { ok: false; reason: string };
export function validatePendingCommand(storageKey: string, raw: string, expectedUserId: number): PendingValidation {
    try {
        const input: unknown = JSON.parse(raw);
        if (!object(input)) return { ok: false, reason: '记录不是对象。' };
        const recordFields = ['schemaVersion', 'userId', 'state', 'resumeState', 'commandType', 'method', 'path', 'idempotencyKey', 'bodyJson', 'originalTransactionType', 'replacementRequestType', 'target', 'refresh', 'submittedAt', 'updatedAt'];
        if (!Object.keys(input).every(field => recordFields.includes(field))) return { ok: false, reason: '记录包含未知字段。' };
        const record = input as unknown as PendingInvestmentCommandV1;
        if (record.schemaVersion !== 1 || record.userId !== expectedUserId || !positiveInteger(record.userId) || !states.has(record.state) || !types.has(record.commandType) || record.method !== 'POST') return { ok: false, reason: '记录版本或状态无效。' };
        if (typeof record.idempotencyKey !== 'string' || record.idempotencyKey.trim() !== record.idempotencyKey || record.idempotencyKey.length < 1 || record.idempotencyKey.length > 100 || storageKey !== pendingStorageKey(record.userId, record.idempotencyKey)) return { ok: false, reason: '幂等键不匹配。' };
        if (record.commandType !== 'REPLACEMENT' && (record.originalTransactionType !== undefined || record.replacementRequestType !== undefined)) return { ok: false, reason: '非更正命令不能包含更正类型证据。' };
        if (record.commandType === 'REPLACEMENT' && (record.originalTransactionType !== record.replacementRequestType)) return { ok: false, reason: '更正类型证据不一致。' };
        if (typeof record.bodyJson !== 'string' || !bodyMatches(record.commandType, record.bodyJson, record.replacementRequestType) || !object(record.target) || !pathMatches(record)) return { ok: false, reason: '请求恢复内容无效。' };
        const successRefreshState = record.state === 'SUCCEEDED_AWAITING_REFRESH' || record.state === 'AUTH_REQUIRED' && record.resumeState === 'SUCCEEDED_AWAITING_REFRESH';
        if (!successRefreshState && record.refresh !== undefined) return { ok: false, reason: '当前状态不能包含刷新目标。' };
        if (record.state === 'AUTH_REQUIRED') {
            if (!record.resumeState || !resumes.has(record.resumeState)) return { ok: false, reason: '认证恢复来源无效。' };
            if (record.resumeState === 'SUCCEEDED_AWAITING_REFRESH' && !refreshMatches(record)) return { ok: false, reason: '刷新目标无效。' };
        } else if (record.resumeState !== undefined) return { ok: false, reason: '非认证状态不能包含恢复来源。' };
        if (record.state === 'SUCCEEDED_AWAITING_REFRESH' && !refreshMatches(record)) return { ok: false, reason: '成功刷新目标无效。' };
        if (!validIsoTimestamp(record.submittedAt) || !validIsoTimestamp(record.updatedAt)) return { ok: false, reason: '恢复元数据无效。' };
        return { ok: true, record };
    } catch { return { ok: false, reason: '记录无法解析。' }; }
}
function validIsoTimestamp(value: unknown): value is string {
    if (typeof value !== 'string') return false;
    const parsed = new Date(value);
    return !Number.isNaN(parsed.getTime()) && parsed.toISOString() === value;
}
export function scanPendingCommands(storage: Storage, userId: number) {
    const records: Array<{ storageKey: string; raw: string; validation: PendingValidation }> = [];
    const start = pendingStoragePrefix(userId);
    for (let index = 0; index < storage.length; index += 1) {
        const storageKey = storage.key(index);
        if (!storageKey?.startsWith(start)) continue;
        const raw = storage.getItem(storageKey);
        if (raw !== null) records.push({ storageKey, raw, validation: validatePendingCommand(storageKey, raw, userId) });
    }
    return records.sort((left, right) => {
        if (left.validation.ok !== right.validation.ok) return left.validation.ok ? 1 : -1;
        return left.validation.ok && right.validation.ok ? left.validation.record.submittedAt.localeCompare(right.validation.record.submittedAt) : left.storageKey.localeCompare(right.storageKey);
    });
}
