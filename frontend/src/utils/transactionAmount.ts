import { isLosslessNumber, parse } from 'lossless-json';

/** Validate decimal text without first rounding it through an IEEE-754 number.
 * Mirrors TransactionWriteRules: scale <= 2, NUMERIC(18,2), and type-specific sign.
 * Scientific notation stays exact too; no financial value becomes a Number.
 */
export function validateTransactionAmount(amount: string, type: string): string {
    const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(amount);
    if (!match) return '请输入有效金额';
    const fraction = match[3] ?? match[4] ?? '';
    const digits = `${match[2] ?? '0'}${fraction}`.replace(/^0+/, '');
    const scale = BigInt(fraction.length) - BigInt(match[5] ?? '0');
    if (scale > 2n) return '金额最多支持两位小数';
    if (digits && BigInt(digits.length) - scale > 16n) return '金额超出允许范围';
    if ((type === 'INCOME' || type === 'EXPENSE') && (!digits || match[1] === '-')) return '收入和支出金额必须大于 0';
    if (type === 'ADJUSTMENT' && !digits) return '调整金额不能为 0';
    return '';
}

/** Canonical scale-2 text keeps equivalent amounts on the same create intent.
 * Validate first: excess input scale must never be hidden by normalization.
 */
export function normalizeTransactionAmount(amount: string): string {
    const error = validateTransactionAmount(amount, 'ADJUSTMENT');
    if (error) throw new Error(error);
    const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(amount)!;
    const fraction = match[3] ?? match[4] ?? '';
    let digits = `${match[2] ?? '0'}${fraction}`.replace(/^0+/, '');
    // Legal NUMERIC(18,2) values require at most 17 padding steps.
    for (let scale = BigInt(fraction.length) - BigInt(match[5] ?? '0'); scale < 2n; scale++) digits += '0';
    const cents = digits.padStart(3, '0');
    return `${match[1] === '-' ? '-' : ''}${cents.slice(0, -2)}.${cents.slice(-2)}`;
}

/** Scoped to ordinary transaction reads. Import's frozen decoder is unchanged.
 * Jackson still emits numeric tokens; capture amount before native JSON parsing.
 * Non-money metadata retains its existing numeric representation.
 */
export function decodeTransactionResponse(raw: string): unknown {
    return parse(raw, (key, value) => {
        if (!isLosslessNumber(value)) return value;
        return key === 'amount' ? value.toString() : Number(value.toString());
    });
}

/** List amounts come from PostgreSQL NUMERIC(18,2), in plain decimal notation. */
export function formatExactTransactionAmount(type: string, amount: string): string {
    const match = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(amount);
    if (!match) throw new Error('服务端流水金额格式无效');
    const negative = type === 'EXPENSE' || (type === 'ADJUSTMENT' && match[1] === '-');
    const integer = match[2]!.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return `${negative ? '-' : ''}¥${integer}.${(match[3] ?? '').padEnd(2, '0')}`;
}
