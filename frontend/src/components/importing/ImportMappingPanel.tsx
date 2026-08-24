import type { ChangeEvent, ReactNode } from 'react';
import { importTargets } from '../../utils/transactionImportMapping';
import type { DiscoveredMappingValues, ImportAccountOption, ImportCategoryOption, ImportTransactionType, TransactionImportColumnSelection } from '../../types/transactionImport';

const typeOptions: ImportTransactionType[] = ['INCOME', 'EXPENSE', 'ADJUSTMENT'];
const typeLabels: Record<ImportTransactionType, string> = { INCOME: '收入', EXPENSE: '支出', ADJUSTMENT: '调整' };

export function ImportMappingPanel({ columns, selection, discovered, accounts, categories, typeMappings, accountMappings, categoryMappings, categoryUsage, busy, onSelectionChange, onTypeChange, onAccountChange, onCategoryChange, onDiscover, onSubmit }: {
    columns: string[]; selection: TransactionImportColumnSelection; discovered: DiscoveredMappingValues | null; accounts: ImportAccountOption[]; categories: ImportCategoryOption[];
    typeMappings: Record<string, ImportTransactionType>; accountMappings: Record<string, number>; categoryMappings: Record<string, number>; categoryUsage: Record<string, ImportTransactionType[]>; busy: boolean;
    onSelectionChange: (target: keyof TransactionImportColumnSelection, source: string) => void; onTypeChange: (source: string, value: ImportTransactionType | '') => void;
    onAccountChange: (source: string, value: number | null) => void; onCategoryChange: (source: string, value: number | null) => void; onDiscover: () => void; onSubmit: () => void;
}) {
    const activeAccounts = accounts.filter(account => account.status === 'ACTIVE' && account.currency === 'CNY');
    const selectableCategories = (source: string) => {
        const usage = new Set(categoryUsage[source] ?? []);
        if (usage.size === 1 && usage.has('INCOME')) return categories.filter(category => category.type === 'INCOME');
        if (usage.size === 1 && usage.has('EXPENSE')) return categories.filter(category => category.type === 'EXPENSE');
        return categories;
    };
    const numericChange = (callback: (value: number | null) => void) => (event: ChangeEvent<HTMLSelectElement>) => callback(event.target.value ? Number(event.target.value) : null);
    return <section className="page-panel import-panel" aria-labelledby="import-mapping-title">
        <div className="section-heading"><div><p className="section-heading__eyebrow">STEP 02</p><h2 id="import-mapping-title">映射字段</h2></div></div>
        <p className="import-copy">先选择来源列，再由同一导入会话读取有限行数发现类型、账户和分类来源值。此步骤不会创建交易或修改账户余额。</p>
        <div className="table-scroll"><table className="data-table import-mapping-table"><thead><tr><th scope="col">目标字段</th><th scope="col">要求</th><th scope="col">来源列</th></tr></thead><tbody>
            {importTargets.map(target => <tr key={target.key}><td>{target.label}</td><td>{target.required ? '必填' : '可选'}</td><td><label className="sr-only" htmlFor={`import-column-${target.key}`}>{target.label}来源列</label><select id={`import-column-${target.key}`} className="field__control" value={selection[target.key]} onChange={event => onSelectionChange(target.key, event.target.value)} disabled={busy}><option value="">{target.required ? '请选择来源列' : '不映射'}</option>{columns.map(column => <option key={column} value={column}>{column}</option>)}</select></td></tr>)}
        </tbody></table></div>
        {!discovered && <div className="data-table__actions import-actions"><button type="button" className="button button--primary" onClick={onDiscover} disabled={busy}>{busy ? '正在发现来源值…' : '保存列映射并发现来源值'}</button></div>}
        {discovered && <div className="import-value-mappings">
            <h3>来源值映射</h3>
            <p className="import-copy">来源值仅用于当前映射界面的去重和选择；后端仍是校验与导入结果的唯一权威来源。</p>
            <MappingRows title="类型" values={discovered.types} render={(source) => <select aria-label={`类型 ${source}`} className="field__control" value={typeMappings[source] ?? ''} onChange={event => onTypeChange(source, event.target.value as ImportTransactionType | '')} disabled={busy}><option value="">请选择类型</option>{typeOptions.map(type => <option key={type} value={type}>{typeLabels[type]}</option>)}</select>} />
            <MappingRows title="账户" values={discovered.accounts} empty={activeAccounts.length === 0 ? '没有可用的 ACTIVE CNY 账户，请先在账户页创建或启用账户。' : undefined} render={(source) => <select aria-label={`账户 ${source}`} className="field__control" value={accountMappings[source] ? String(accountMappings[source]) : ''} onChange={numericChange(value => onAccountChange(source, value))} disabled={busy || activeAccounts.length === 0}><option value="">请选择账户</option>{activeAccounts.map(account => <option key={account.id} value={account.id}>{account.name} · {account.type} · CNY · #{account.id}</option>)}</select>} />
            <MappingRows title="分类" values={discovered.categories} empty={categories.length === 0 ? '没有当前用户可见的分类，请先准备分类。' : undefined} render={(source) => <select aria-label={`分类 ${source}`} className="field__control" value={categoryMappings[source] ? String(categoryMappings[source]) : ''} onChange={numericChange(value => onCategoryChange(source, value))} disabled={busy || categories.length === 0}><option value="">请选择分类</option>{selectableCategories(source).map(category => <option key={category.id} value={category.id}>{category.name} · {category.type} · #{category.id}</option>)}</select>} />
            <div className="data-table__actions import-actions"><button type="button" className="button button--primary" onClick={onSubmit} disabled={busy}>{busy ? '正在提交正式映射…' : '提交正式映射并生成预览'}</button></div>
        </div>}
    </section>;
}

function MappingRows({ title, values, render, empty }: { title: string; values: string[]; render: (source: string) => ReactNode; empty?: string }) {
    return <section className="import-value-group" aria-label={`${title}来源值映射`}><h4>{title}</h4>{empty && <p className="import-inline-error" role="alert">{empty}</p>}<div className="table-scroll"><table className="data-table"><thead><tr><th scope="col">来源值</th><th scope="col">映射目标</th></tr></thead><tbody>{values.map(value => <tr key={value}><td>{value}</td><td>{render(value)}</td></tr>)}</tbody></table></div></section>;
}
