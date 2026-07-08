export const currencyFormatter = new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
});

const transactionTypeLabels: Record<string, string> = {
    INCOME: '收入',
    EXPENSE: '支出',
    ADJUSTMENT: '调整',
};

export function formatCurrency(value: number | null | undefined) {
    return currencyFormatter.format(value ?? 0);
}

export function formatTransactionType(type: string) {
    return transactionTypeLabels[type] ?? type;
}

export function formatTransactionAmount(type: string, amount: number | null | undefined) {
    const value = amount ?? 0;
    if (type === 'INCOME') return formatCurrency(Math.abs(value));
    if (type === 'EXPENSE') return formatCurrency(-Math.abs(value));
    return formatCurrency(value);
}

export function transactionAmountColor(type: string) {
    if (type === 'INCOME') return '#00b894';
    if (type === 'EXPENSE') return '#e17055';
    return '#0984e3';
}
