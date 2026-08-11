import type { InvestmentCorrectionStatus, InvestmentFreshness, InvestmentReceiptApplicability, InvestmentTransactionType, InvestmentWarning } from '../types/investment';

function groupInteger(value: string) { return value.replace(/\B(?=(\d{3})+(?!\d))/g, ','); }

export function formatDecimalString(value: string | null | undefined) {
    if (value === null || value === undefined || value.trim() === '') return '暂无';
    const normalized = value.trim();
    const match = normalized.match(/^(-?)(\d+)(?:\.(\d+))?$/);
    if (!match) return normalized;
    const [, sign, integer, fraction] = match;
    return `${sign}${groupInteger(integer)}${fraction === undefined ? '' : `.${fraction}`}`;
}

export function formatInvestmentMoney(value: string | null | undefined, currency = 'CNY') {
    const decimal = formatDecimalString(value);
    if (decimal === '暂无') return decimal;
    return currency === 'CNY' ? `￥${decimal}` : `${currency} ${decimal}`;
}

export function formatInvestmentQuantity(value: string | null | undefined) {
    if (value === null || value === undefined || value.trim() === '') return '暂无';
    const normalized = value.trim();
    const match = normalized.match(/^(-?)(\d+)(?:\.(\d+))?$/);
    if (!match) return normalized;
    const fraction = (match[3] ?? '').replace(/0+$/, '');
    const integer = match[2].replace(/^0+(?=\d)/, '');
    const isZero = integer === '0' && fraction === '';
    return `${isZero ? '' : match[1]}${groupInteger(integer)}${fraction ? `.${fraction}` : ''}`;
}

const correctionLabels: Record<InvestmentCorrectionStatus, string> = { UNCHANGED: '原始', REVERSED: '已冲正', REPLACED: '已替换' };
const transactionLabels: Record<InvestmentTransactionType, string> = { OPENING_POSITION: '期初持仓', BUY: '买入', SELL: '卖出', DIVIDEND: '分红' };
const freshnessLabels: Record<InvestmentFreshness, string> = { FRESH: '最新', STALE: '已过期', PARTIAL: '部分可用', UNAVAILABLE: '暂不可用' };
const receiptLabels: Record<InvestmentReceiptApplicability, string> = { POSTING_TIME: '原始入账回执', CORRECTION_FINAL: '纠正完成回执', NOT_APPLICABLE: '不适用' };
const auditEventLabels: Record<string, string> = { ORIGINAL_POSTING: '原始入账', STANDALONE_REVERSAL: '冲正', REPLACEMENT_COMMAND: '替换操作' };
const auditFactRoleLabels: Record<string, string> = { GROUPED_REVERSAL: '内部冲正事实', REPLACEMENT_FACT: '替换事实' };
const warningLabels: Record<string, string> = { QUOTE_STALE: '行情数据已过期', FX_STALE: '汇率数据已过期', QUOTE_MISSING: '暂无可用行情', FX_MISSING: '暂无可用汇率' };

function unknownLabel(prefix: string, value: string | null | undefined) { return `${prefix}（${value || 'UNKNOWN'}）`; }

export const investmentCorrectionStatusLabel = (value: string | null | undefined) => correctionLabels[value as InvestmentCorrectionStatus] ?? unknownLabel('未知纠正状态', value);
export const investmentTransactionTypeLabel = (value: string | null | undefined) => transactionLabels[value as InvestmentTransactionType] ?? (value === 'REVERSAL' ? '契约异常（REVERSAL）' : unknownLabel('未知交易类型', value));
export const investmentFreshnessLabel = (value: string | null | undefined) => freshnessLabels[value as InvestmentFreshness] ?? unknownLabel('未知估值状态', value);
export const investmentReceiptApplicabilityLabel = (value: string | null | undefined) => receiptLabels[value as InvestmentReceiptApplicability] ?? unknownLabel('未知回执适用性', value);
export const investmentAuditEventLabel = (value: string) => auditEventLabels[value] ?? unknownLabel('未知审计事件', value);
export const investmentAuditFactRoleLabel = (value: string) => auditFactRoleLabels[value] ?? unknownLabel('未知审计事实', value);
export const investmentWarningMessages = (warnings: readonly InvestmentWarning[] | null | undefined) => Array.from(new Set((warnings ?? []).map(warning => warningLabels[warning.code] ?? '市场参考数据存在提示')));

export function getInvestmentReadErrorMessage(error: unknown, context: 'filter' | 'detail' | 'general') {
    const status = (error as { response?: { status?: number } }).response?.status;
    if (status === 400 && context === 'filter') return '筛选条件无效，请检查后重试';
    if (status === 404 && context === 'detail') return '该投资记录不存在或不可访问';
    if (status === 401) return '登录状态已失效，请重新登录';
    return '投资数据暂时无法读取，请稍后重试';
}

export function formatInvestmentDateTime(value: string | null | undefined) {
    if (!value) return '暂无';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '暂无';
    return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date);
}
