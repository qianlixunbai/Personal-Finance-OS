import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties, FormEvent, ReactNode } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { getErrorMessage } from '../utils/error';
import { formatTransactionAmount, formatTransactionType, transactionAmountColor } from '../utils/format';

type TransactionType = 'INCOME' | 'EXPENSE' | 'ADJUSTMENT';

interface Transaction {
    id: number;
    accountId: number;
    categoryId: number;
    type: TransactionType;
    amount: number;
    currency: string;
    description?: string;
    transactedAt: string;
}

interface PageResult<T> {
    records: T[];
    total: number;
    page: number;
    size: number;
}

interface Account {
    id: number;
    name: string;
    status: string;
}

interface Category {
    id: number;
    name: string;
    type: string;
}

interface TransactionForm {
    accountId: string;
    categoryId: string;
    type: TransactionType;
    amount: string;
    currency: string;
    description: string;
    transactedAt: string;
}

interface Filters {
    type: string;
    accountId: string;
    categoryId: string;
    start: string;
    end: string;
}

const pageSize = 20;
const typeOptions: TransactionType[] = ['INCOME', 'EXPENSE', 'ADJUSTMENT'];

const emptyForm = (): TransactionForm => ({
    accountId: '',
    categoryId: '',
    type: 'EXPENSE',
    amount: '',
    currency: 'CNY',
    description: '',
    transactedAt: toInputDateTime(new Date()),
});

function toInputDateTime(value: string | Date) {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    const pad = (num: number) => String(num).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function toLocalDateTime(value: string) {
    return value.length === 16 ? `${value}:00` : value;
}

export default function Transactions() {
    const [transactions, setTransactions] = useState<Transaction[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [categories, setCategories] = useState<Category[]>([]);
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<number | null>(null);
    const [form, setForm] = useState<TransactionForm>(emptyForm);
    const [filters, setFilters] = useState<Filters>({ type: '', accountId: '', categoryId: '', start: '', end: '' });
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    const accountNameById = useMemo(() => new Map(accounts.map(account => [account.id, account.name])), [accounts]);
    const categoryNameById = useMemo(() => new Map(categories.map(category => [category.id, category.name])), [categories]);

    const selectableCategories = useMemo(() => {
        if (form.type === 'ADJUSTMENT') return categories;
        return categories.filter(category => category.type === form.type);
    }, [categories, form.type]);

    const fetchTransactions = async (targetPage = page) => {
        const params: Record<string, string | number> = { page: targetPage, size: pageSize };
        if (filters.type) params.type = filters.type;
        if (filters.accountId) params.accountId = filters.accountId;
        if (filters.categoryId) params.categoryId = filters.categoryId;
        if (filters.start) params.start = toLocalDateTime(filters.start);
        if (filters.end) params.end = toLocalDateTime(filters.end);

        const res = await api.get('/transactions/page', { params });
        const data: PageResult<Transaction> = res.data.data;
        setTransactions(data.records || []);
        setTotal(data.total || 0);
        setPage(data.page || targetPage);
    };

    const fetchOptions = async () => {
        const [accountRes, categoryRes] = await Promise.all([
            api.get('/accounts'),
            api.get('/categories'),
        ]);
        setAccounts(accountRes.data.data || []);
        setCategories(categoryRes.data.data || []);
    };

    useEffect(() => {
        fetchOptions().catch(err => setError(getErrorMessage(err, '操作失败，请稍后重试')));
        fetchTransactions(1).catch(err => setError(getErrorMessage(err, '操作失败，请稍后重试')));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const validate = () => {
        const amount = Number(form.amount);
        if (!form.accountId) return '请选择账户';
        if (!form.categoryId) return '请选择分类';
        if (!form.transactedAt) return '请选择交易时间';
        if (!form.amount || Number.isNaN(amount)) return '请输入有效金额';
        if ((form.type === 'INCOME' || form.type === 'EXPENSE') && amount <= 0) return '收入和支出金额必须大于 0';
        if (form.type === 'ADJUSTMENT' && amount === 0) return '调整金额不能为 0';
        if (form.type === 'ADJUSTMENT' && !form.description.trim()) return '调整流水必须填写说明';
        return '';
    };

    const resetForm = () => {
        setForm(emptyForm());
        setEditingId(null);
    };

    const openCreateForm = () => {
        resetForm();
        setShowForm(true);
        setError('');
        setSuccess('');
    };

    const closeForm = () => {
        resetForm();
        setShowForm(false);
    };

    const submit = async (e: FormEvent) => {
        e.preventDefault();
        setError('');
        setSuccess('');

        const validationError = validate();
        if (validationError) {
            setError(validationError);
            return;
        }

        const payload = {
            accountId: Number(form.accountId),
            categoryId: Number(form.categoryId),
            type: form.type,
            amount: Number(form.amount),
            currency: form.currency || 'CNY',
            description: form.description.trim() || null,
            transactedAt: toLocalDateTime(form.transactedAt),
        };

        try {
            if (editingId) {
                await api.put(`/transactions/${editingId}`, payload);
                setSuccess('流水已更新');
            } else {
                await api.post('/transactions', payload);
                setSuccess('流水已创建');
            }
            resetForm();
            setShowForm(false);
            await fetchOptions();
            await fetchTransactions(page);
        } catch (err) {
            setError(getErrorMessage(err, '操作失败，请稍后重试'));
        }
    };

    const edit = (tx: Transaction) => {
        setEditingId(tx.id);
        setForm({
            accountId: String(tx.accountId),
            categoryId: String(tx.categoryId),
            type: tx.type,
            amount: String(tx.amount),
            currency: tx.currency || 'CNY',
            description: tx.description || '',
            transactedAt: toInputDateTime(tx.transactedAt),
        });
        setShowForm(true);
        setError('');
        setSuccess('');
    };

    const remove = async (tx: Transaction) => {
        if (!confirm('确定删除这条流水吗？')) return;

        try {
            await api.delete(`/transactions/${tx.id}`);
            const nextPage = transactions.length === 1 && page > 1 ? page - 1 : page;
            setSuccess('流水已删除');
            setError('');
            await fetchOptions();
            await fetchTransactions(nextPage);
        } catch (err) {
            setError(getErrorMessage(err, '操作失败，请稍后重试'));
        }
    };

    const search = async (e: FormEvent) => {
        e.preventDefault();
        setError('');
        setSuccess('');
        try {
            await fetchTransactions(1);
        } catch (err) {
            setError(getErrorMessage(err, '操作失败，请稍后重试'));
        }
    };

    const goPage = async (nextPage: number) => {
        if (nextPage < 1 || nextPage > totalPages) return;
        try {
            await fetchTransactions(nextPage);
        } catch (err) {
            setError(getErrorMessage(err, '操作失败，请稍后重试'));
        }
    };

    const totalPages = Math.max(1, Math.ceil(total / pageSize));

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>交易流水</h2>
                <button onClick={showForm ? closeForm : openCreateForm} style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>
                    {showForm ? '收起表单' : '新增流水'}
                </button>
            </div>

            {error && <AlertMessage type="error">{error}</AlertMessage>}
            {success && <AlertMessage type="success">{success}</AlertMessage>}

            <form onSubmit={search} style={{ background: '#fff', padding: 20, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, alignItems: 'end' }}>
                    <Field label="类型">
                        <select value={filters.type} onChange={e => setFilters({ ...filters, type: e.target.value })} style={controlStyle}>
                            <option value="">全部</option>
                            {typeOptions.map(type => <option key={type} value={type}>{formatTransactionType(type)}</option>)}
                        </select>
                    </Field>
                    <Field label="账户">
                        <select value={filters.accountId} onChange={e => setFilters({ ...filters, accountId: e.target.value })} style={controlStyle}>
                            <option value="">全部</option>
                            {accounts.map(account => <option key={account.id} value={account.id}>{account.name}</option>)}
                        </select>
                    </Field>
                    <Field label="分类">
                        <select value={filters.categoryId} onChange={e => setFilters({ ...filters, categoryId: e.target.value })} style={controlStyle}>
                            <option value="">全部</option>
                            {categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
                        </select>
                    </Field>
                    <Field label="开始时间">
                        <input type="datetime-local" value={filters.start} onChange={e => setFilters({ ...filters, start: e.target.value })} style={controlStyle} />
                    </Field>
                    <Field label="结束时间">
                        <input type="datetime-local" value={filters.end} onChange={e => setFilters({ ...filters, end: e.target.value })} style={controlStyle} />
                    </Field>
                    <button type="submit" style={{ padding: '10px 18px', background: '#2d3436', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>筛选</button>
                </div>
            </form>

            {showForm && (
                <form onSubmit={submit} style={{ background: '#fff', padding: 24, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <h3 style={{ marginBottom: 16 }}>{editingId ? '编辑流水' : '新增流水'}</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
                        <Field label="类型">
                            <select value={form.type} onChange={e => setForm({ ...form, type: e.target.value as TransactionType, categoryId: '' })} style={controlStyle}>
                                {typeOptions.map(type => <option key={type} value={type}>{formatTransactionType(type)}</option>)}
                            </select>
                        </Field>
                        <Field label="账户">
                            <select value={form.accountId} onChange={e => setForm({ ...form, accountId: e.target.value })} required style={controlStyle}>
                                <option value="">请选择账户</option>
                                {accounts.filter(account => account.status === 'ACTIVE').map(account => <option key={account.id} value={account.id}>{account.name}</option>)}
                            </select>
                        </Field>
                        <Field label="分类">
                            <select value={form.categoryId} onChange={e => setForm({ ...form, categoryId: e.target.value })} required style={controlStyle}>
                                <option value="">请选择分类</option>
                                {selectableCategories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
                            </select>
                        </Field>
                        <Field label="金额">
                            <input type="number" step="0.01" value={form.amount} onChange={e => setForm({ ...form, amount: e.target.value })} required style={controlStyle} />
                        </Field>
                        <Field label="币种">
                            <input value={form.currency} onChange={e => setForm({ ...form, currency: e.target.value })} style={controlStyle} />
                        </Field>
                        <Field label="交易时间">
                            <input type="datetime-local" value={form.transactedAt} onChange={e => setForm({ ...form, transactedAt: e.target.value })} required style={controlStyle} />
                        </Field>
                    </div>
                    <div style={{ marginTop: 12 }}>
                        <Field label="说明">
                            <input value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} style={controlStyle} placeholder={form.type === 'ADJUSTMENT' ? '调整流水必须填写说明' : '可选'} />
                        </Field>
                    </div>
                    <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
                        <button type="submit" style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>{editingId ? '保存修改' : '保存'}</button>
                        {editingId && <button type="button" onClick={closeForm} style={{ padding: '10px 20px', background: '#636e72', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>取消编辑</button>}
                    </div>
                </form>
            )}

            <table style={{ width: '100%', borderCollapse: 'collapse', background: '#fff', borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                <thead>
                    <tr>
                        <th style={thStyle}>时间</th>
                        <th style={thStyle}>类型</th>
                        <th style={thStyle}>账户</th>
                        <th style={thStyle}>分类</th>
                        <th style={thStyle}>金额</th>
                        <th style={thStyle}>币种</th>
                        <th style={thStyle}>说明</th>
                        <th style={thStyle}>操作</th>
                    </tr>
                </thead>
                <tbody>
                    {transactions.map(tx => (
                        <tr key={tx.id}>
                            <td style={tdStyle}>{tx.transactedAt?.replace('T', ' ')}</td>
                            <td style={tdStyle}>{formatTransactionType(tx.type)}</td>
                            <td style={tdStyle}>{accountNameById.get(tx.accountId) || tx.accountId}</td>
                            <td style={tdStyle}>{categoryNameById.get(tx.categoryId) || tx.categoryId}</td>
                            <td style={{ ...tdStyle, fontWeight: 600, color: transactionAmountColor(tx.type) }}>{formatTransactionAmount(tx.type, tx.amount)}</td>
                            <td style={tdStyle}>{tx.currency}</td>
                            <td style={tdStyle}>{tx.description || '-'}</td>
                            <td style={tdStyle}>
                                <div style={{ display: 'flex', gap: 8 }}>
                                    <button onClick={() => edit(tx)} style={{ padding: '6px 12px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>编辑</button>
                                    <button onClick={() => remove(tx)} style={{ padding: '6px 12px', background: '#e17055', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>删除</button>
                                </div>
                            </td>
                        </tr>
                    ))}
                    {transactions.length === 0 && (
                        <EmptyTableRow colSpan={8} message="暂无流水" />
                    )}
                </tbody>
            </table>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 16 }}>
                <span style={{ color: '#636e72' }}>第 {page} / {totalPages} 页，共 {total} 条</span>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={() => goPage(page - 1)} disabled={page <= 1} style={pageButtonStyle}>上一页</button>
                    <button onClick={() => goPage(page + 1)} disabled={page >= totalPages} style={pageButtonStyle}>下一页</button>
                </div>
            </div>
        </div>
    );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
    return (
        <label style={{ display: 'block' }}>
            <span style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>{label}</span>
            {children}
        </label>
    );
}

const controlStyle: CSSProperties = {
    width: '100%',
    padding: '10px 12px',
    border: '1px solid #ddd',
    borderRadius: 8,
    fontSize: 14,
};

const thStyle: CSSProperties = {
    padding: '12px 16px',
    textAlign: 'left',
    borderBottom: '1px solid #eee',
};

const tdStyle: CSSProperties = {
    padding: '12px 16px',
    borderBottom: '1px solid #eee',
};

const pageButtonStyle: CSSProperties = {
    padding: '8px 14px',
    background: '#fff',
    border: '1px solid #ddd',
    borderRadius: 8,
    cursor: 'pointer',
};
