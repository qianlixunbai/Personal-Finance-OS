import type { InvestmentCommandDraft, InvestmentCommandType } from '../types/investmentCommand';

const decimal = /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/;

export function validateDecimalString(value: string, scale: number): string | null {
    if (!decimal.test(value) || value.startsWith('+')) return '请输入普通定点小数字符串。';
    const fraction = value.split('.')[1];
    return fraction && fraction.length > scale ? `最多保留 ${scale} 位小数。` : null;
}

export function compareDecimalStrings(left: string, right: string): number {
    const parts = (value: string) => {
        const negative = value.startsWith('-');
        const unsigned = negative ? value.slice(1) : value;
        const [integer, fraction = ''] = unsigned.split('.');
        return { negative, integer: integer.replace(/^0+(?=\d)/, ''), fraction: fraction.replace(/0+$/, '') };
    };
    const a = parts(left); const b = parts(right);
    if (a.negative !== b.negative) return a.negative ? -1 : 1;
    const direction = a.negative ? -1 : 1;
    if (a.integer.length !== b.integer.length) return a.integer.length > b.integer.length ? direction : -direction;
    if (a.integer !== b.integer) return a.integer > b.integer ? direction : -direction;
    const scale = Math.max(a.fraction.length, b.fraction.length);
    const af = a.fraction.padEnd(scale, '0'); const bf = b.fraction.padEnd(scale, '0');
    return af === bf ? 0 : af > bf ? direction : -direction;
}

function positive(value: string, scale: number, label: string): string | null {
    const invalid = validateDecimalString(value, scale);
    if (invalid) return `${label}${invalid}`;
    return /^-?0(?:\.0+)?$/.test(value) || value.startsWith('-') ? `${label}必须大于 0。` : null;
}

function addDecimalStrings(left: string, right: string) {
    const scale = Math.max((left.split('.')[1] ?? '').length, (right.split('.')[1] ?? '').length);
    const toUnits = (value: string) => BigInt(value.replace('.', '') + '0'.repeat(scale - (value.split('.')[1] ?? '').length));
    const units = toUnits(left) + toUnits(right);
    if (scale === 0) return units.toString();
    const digits = units.toString().padStart(scale + 1, '0');
    return `${digits.slice(0, -scale)}.${digits.slice(-scale)}`;
}

function nonNegative(value: string, scale: number, label: string): string | null {
    const invalid = validateDecimalString(value, scale);
    if (invalid) return `${label}${invalid}`;
    return value.startsWith('-') ? `${label}不能小于 0。` : null;
}

export function normalizeOptionalText(value: string, maxLength: number): string | null {
    const normalized = value.trim();
    if (normalized.length > maxLength) return null;
    return normalized || null;
}

export function validateDraft(draft: InvestmentCommandDraft): string | null {
    if (draft.commandType === 'REVERSAL') {
        return draft.reason.trim() !== draft.reason || draft.reason.trim().length < 1 || draft.reason.length > 500 ? '纠正原因需为 1–500 个字符，且不能有首尾空格。' : null;
    }
    if (draft.commandType === 'FIRST_BUY' && (!draft.accountId || !draft.instrumentId)) return '请选择账户和投资标的。';
    if ((draft.commandType === 'BUY' || draft.commandType === 'SELL' || draft.commandType === 'DIVIDEND') && !draft.assetId) return '缺少持仓目标，无法提交。';
    if (draft.commandType === 'REPLACEMENT' && !draft.logicalTransactionId) return '缺少原始交易，无法更正。';
    if (draft.commandType === 'REPLACEMENT' && (!draft.replacementTransactionType || !draft.replacementRequestType || draft.replacementTransactionType !== draft.replacementRequestType)) return '更正请求类型证据无效，不能安全更正。';
    const trade = draft.commandType === 'FIRST_BUY' || draft.commandType === 'BUY' || draft.commandType === 'SELL' || draft.commandType === 'REPLACEMENT' && draft.replacementRequestType !== 'DIVIDEND';
    const amount = trade ? positive(draft.quantity, 8, '数量') ?? positive(draft.unitPrice, 8, '单价') : positive(draft.grossAmount, 2, '分红总额');
    if (amount) return amount;
    const fee = nonNegative(draft.feeAmount, 2, '费用');
    if (fee) return fee;
    const tax = nonNegative(draft.taxAmount, 2, '税费');
    if (tax) return tax;
    if ((draft.commandType === 'DIVIDEND' || draft.commandType === 'REPLACEMENT' && draft.replacementRequestType === 'DIVIDEND')
        && compareDecimalStrings(addDecimalStrings(draft.feeAmount, draft.taxAmount), draft.grossAmount) > 0) return '费用与税费之和不能大于分红总额。';
    if (draft.externalReference.trim().length > 100) return '外部参考号最多 100 个字符。';
    if (draft.note.trim().length > 500) return '备注最多 500 个字符。';
    if (draft.commandType === 'REPLACEMENT' && (draft.reason.trim() !== draft.reason || draft.reason.trim().length < 1 || draft.reason.length > 500)) return '纠正原因需为 1–500 个字符，且不能有首尾空格。';
    return null;
}

export function createCommandBody(draft: InvestmentCommandDraft): Record<string, string | number | null> {
    const common = { feeAmount: draft.feeAmount, taxAmount: draft.taxAmount };
    const metadata = { externalReference: normalizeOptionalText(draft.externalReference, 100), note: normalizeOptionalText(draft.note, 500) };
    switch (draft.commandType) {
        case 'FIRST_BUY': return { accountId: draft.accountId!, instrumentId: draft.instrumentId!, quantity: draft.quantity, unitPrice: draft.unitPrice, ...common };
        case 'BUY': case 'SELL': return { quantity: draft.quantity, unitPrice: draft.unitPrice, ...common };
        case 'DIVIDEND': return { grossAmount: draft.grossAmount, ...common, ...metadata };
        case 'REVERSAL': return { reason: draft.reason };
        case 'REPLACEMENT':
            if (!draft.replacementRequestType || draft.replacementRequestType !== draft.replacementTransactionType) throw new Error('更正请求类型证据无效，不能安全更正。');
            return draft.replacementRequestType !== 'DIVIDEND' ? { quantity: draft.quantity, unitPrice: draft.unitPrice, ...common, ...metadata, reason: draft.reason } : { grossAmount: draft.grossAmount, ...common, ...metadata, reason: draft.reason };
    }
}

export function createCommandBodyJson(draft: Pick<InvestmentCommandDraft, 'quantity' | 'unitPrice' | 'feeAmount' | 'taxAmount'>): string {
    return JSON.stringify({ quantity: draft.quantity, unitPrice: draft.unitPrice, feeAmount: draft.feeAmount, taxAmount: draft.taxAmount });
}

export function canOfferPositionAction(status: 'OPEN' | 'CLOSED', action: 'BUY' | 'SELL' | 'DIVIDEND') {
    return action !== 'SELL' || status === 'OPEN';
}

export function canOfferBuyWithAuthoritativeEligibility(accountStatus?: string, accountType?: string, instrumentStatus?: string, assetClass?: string) {
    if (accountStatus !== 'ACTIVE' || instrumentStatus !== 'ACTIVE' || !accountType || !assetClass) return false;
    if (accountType === 'BROKERAGE') return assetClass === 'STOCK' || assetClass === 'ETF' || assetClass === 'FUND' || assetClass === 'BOND';
    return accountType === 'CRYPTO_WALLET' && assetClass === 'CRYPTO';
}

export function commandPath(commandType: InvestmentCommandType, draft: InvestmentCommandDraft): string {
    if (commandType === 'FIRST_BUY') return '/investment/positions';
    if (commandType === 'BUY') return `/investment/positions/${draft.assetId}/buy`;
    if (commandType === 'SELL') return `/investment/positions/${draft.assetId}/sell`;
    if (commandType === 'DIVIDEND') return `/investment/positions/${draft.assetId}/dividends`;
    if (commandType === 'REVERSAL') return `/investment/transactions/${draft.logicalTransactionId}/reversal`;
    return `/investment/transactions/${draft.logicalTransactionId}/replacement`;
}
