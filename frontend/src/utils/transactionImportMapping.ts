import type { DiscoveredMappingValues, ImportCanonicalTarget, ImportTransactionType, TransactionImportColumnSelection, TransactionImportMapping, TransactionImportPreviewRow } from '../types/transactionImport';

export const importTargets: Array<{ key: ImportCanonicalTarget; label: string; required: boolean }> = [
    { key: 'date', label: '日期', required: true }, { key: 'time', label: '时间', required: false },
    { key: 'type', label: '类型', required: true }, { key: 'amount', label: '金额', required: true },
    { key: 'account', label: '账户', required: true }, { key: 'category', label: '分类', required: true },
    { key: 'description', label: '说明', required: false }, { key: 'currency', label: '币种', required: false },
];

export const emptyColumnSelection = (): TransactionImportColumnSelection => ({ date: '', time: '', type: '', amount: '', account: '', category: '', description: '', currency: '' });

export function normalizeSourceValue(value: string) { return value.normalize('NFC').trim(); }

export function validateColumnSelection(columns: string[], selection: TransactionImportColumnSelection): string | null {
    const used = new Set<string>();
    for (const target of importTargets) {
        const source = selection[target.key];
        if (target.required && !source) return `请为${target.label}选择来源列`;
        if (!source) continue;
        if (!columns.includes(source)) return '所选来源列已不在当前文件中';
        if (used.has(source)) return '同一来源列不能映射到多个字段';
        used.add(source);
    }
    return null;
}

export function buildColumnMappings(columns: string[], selection: TransactionImportColumnSelection): Record<string, string> {
    const mappings: Record<string, string> = {};
    const used = new Set<string>();
    for (const target of importTargets) {
        const source = selection[target.key];
        if (source) { mappings[target.key] = source; used.add(source); }
    }
    for (const column of columns) if (!used.has(column)) mappings[column] = 'IGNORE';
    return mappings;
}

function pushDistinct(values: Map<string, string>, value: string | undefined) {
    if (typeof value !== 'string') return;
    const normalized = normalizeSourceValue(value);
    if (normalized && !values.has(normalized)) values.set(normalized, normalized);
}

export function collectMappingValues(rows: Array<Pick<TransactionImportPreviewRow, 'sourceValues'>>, selection: TransactionImportColumnSelection): DiscoveredMappingValues {
    const types = new Map<string, string>(); const accounts = new Map<string, string>(); const categories = new Map<string, string>();
    for (const row of rows) {
        pushDistinct(types, row.sourceValues[selection.type]);
        pushDistinct(accounts, row.sourceValues[selection.account]);
        pushDistinct(categories, row.sourceValues[selection.category]);
    }
    return { types: [...types.values()], accounts: [...accounts.values()], categories: [...categories.values()] };
}

export function collectCategoryTypeUsage(rows: Array<Pick<TransactionImportPreviewRow, 'sourceValues'>>, selection: TransactionImportColumnSelection, typeMappings: Record<string, ImportTransactionType>) {
    const usage = new Map<string, Set<ImportTransactionType>>();
    for (const row of rows) {
        const category = normalizeSourceValue(row.sourceValues[selection.category] ?? '');
        const type = typeMappings[normalizeSourceValue(row.sourceValues[selection.type] ?? '')];
        if (!category || !type) continue;
        const types = usage.get(category) ?? new Set<ImportTransactionType>();
        types.add(type); usage.set(category, types);
    }
    return Object.fromEntries([...usage].map(([category, types]) => [category, [...types]])) as Record<string, ImportTransactionType[]>;
}

export function validateValueMappings(values: DiscoveredMappingValues, typeMappings: Record<string, ImportTransactionType>, accountMappings: Record<string, number>, categoryMappings: Record<string, number>, categoryUsage: Record<string, ImportTransactionType[]> = {}): string | null {
    if (values.types.some(value => !typeMappings[value])) return '请完成所有类型映射';
    if (values.accounts.some(value => !Number.isSafeInteger(accountMappings[value]) || accountMappings[value] <= 0)) return '请完成所有账户映射';
    if (values.categories.some(value => !Number.isSafeInteger(categoryMappings[value]) || categoryMappings[value] <= 0)) return '请完成所有分类映射';
    if (values.categories.some(value => new Set(categoryUsage[value] ?? []).size > 1 && (categoryUsage[value] ?? []).includes('INCOME') && (categoryUsage[value] ?? []).includes('EXPENSE'))) return '同一分类来源值同时用于收入和支出，请在源文件中区分后重新上传';
    return null;
}

export function buildMapping(columns: string[], selection: TransactionImportColumnSelection, typeMappings: Record<string, ImportTransactionType>, accountMappings: Record<string, number>, categoryMappings: Record<string, number>): TransactionImportMapping {
    return { columnMappings: buildColumnMappings(columns, selection), typeMappings, accountMappings, categoryMappings };
}
