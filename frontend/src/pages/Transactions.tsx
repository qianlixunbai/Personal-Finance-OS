import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties, FormEvent, ReactNode } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { getErrorMessage } from '../utils/error';
import { formatTransactionAmount, formatTransactionType, transactionAmountColor } from '../utils/format';

interface Transaction {
    id: number;
    accountId: number;
    categoryId: number;
    type: string;
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
}

interface Category {
    id: number;
    name: string;
}

interface Filters {
    type: string;
    accountId: string;
    categoryId: string;
    start: string;
    end: string;
}

const pageSize = 20;
const typeOptions = ['INCOME', 'EXPENSE', 'ADJUSTMENT'];

function toLocalDateTime(value: string) {
    return value.length === 16 ? `${value}:00` : value;
}

export default function Transactions() {
    const [transactions, setTransactions] = useState<Transaction[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [categories, setCategories] = useState<Category[]>([]);
    const [filters, setFilters] = useState<Filters>({ type: '', accountId: '', categoryId: '', start: '', end: '' });
    const [error, setError] = useState('');

    const accountNameById = useMemo(() => new Map(accounts.map(account => [account.id, account.name])), [accounts]);
    const categoryNameById = useMemo(() => new Map(categories.map(category => [category.id, category.name])), [categories]);

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

    const search = async (event: FormEvent) => {
        event.preventDefault();
        setError('');
        try {
            await fetchTransactions(1);
        } catch (err) {
            setError(getErrorMessage(err, '筛选失败，请稍后重试'));
        }
    };

    const totalPages = Math.max(1, Math.ceil(total / pageSize));

    const goPage = async (nextPage: number) => {
        if (nextPage < 1 || nextPage > totalPages) return;
        try {
            await fetchTransactions(nextPage);
        } catch (err) {
            setError(getErrorMessage(err, '分页加载失败，请稍后重试'));
        }
    };

    return (
        <div>
            <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>交易流水</h2>
            </div>

            {error && <AlertMessage type="error">{error}</AlertMessage>}

            <form onSubmit={search} style={{ background: '#fff', padding: 20, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, alignItems: 'end' }}>
                    <Field label="类型">
                        <select value={filters.type} onChange={event => setFilters({ ...filters, type: event.target.value })} style={controlStyle}>
                            <option value="">全部</option>
                            {typeOptions.map(type => <option key={type} value={type}>{formatTransactionType(type)}</option>)}
                        </select>
                    </Field>
                    <Field label="账户">
                        <select value={filters.accountId} onChange={event => setFilters({ ...filters, accountId: event.target.value })} style={controlStyle}>
                            <option value="">全部</option>
                            {accounts.map(account => <option key={account.id} value={account.id}>{account.name}</option>)}
                        </select>
                    </Field>
                    <Field label="分类">
                        <select value={filters.categoryId} onChange={event => setFilters({ ...filters, categoryId: event.target.value })} style={controlStyle}>
                            <option value="">全部</option>
                            {categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}
                        </select>
                    </Field>
                    <Field label="开始时间">
                        <input type="datetime-local" value={filters.start} onChange={event => setFilters({ ...filters, start: event.target.value })} style={controlStyle} />
                    </Field>
                    <Field label="结束时间">
                        <input type="datetime-local" value={filters.end} onChange={event => setFilters({ ...filters, end: event.target.value })} style={controlStyle} />
                    </Field>
                    <button type="submit" style={{ padding: '10px 18px', background: '#2d3436', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>筛选</button>
                </div>
            </form>

            <div className="table-scroll">
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
                        </tr>
                    </thead>
                    <tbody>
                        {transactions.map(transaction => (
                            <tr key={transaction.id}>
                                <td style={tdStyle}>{transaction.transactedAt?.replace('T', ' ')}</td>
                                <td style={tdStyle}>{formatTransactionType(transaction.type)}</td>
                                <td style={tdStyle}>{accountNameById.get(transaction.accountId) || transaction.accountId}</td>
                                <td style={tdStyle}>{categoryNameById.get(transaction.categoryId) || transaction.categoryId}</td>
                                <td style={{ ...tdStyle, fontWeight: 600, color: transactionAmountColor(transaction.type) }}>{formatTransactionAmount(transaction.type, transaction.amount)}</td>
                                <td style={tdStyle}>{transaction.currency}</td>
                                <td style={tdStyle}>{transaction.description || '-'}</td>
                            </tr>
                        ))}
                        {transactions.length === 0 && <EmptyTableRow colSpan={7} message="暂无流水" />}
                    </tbody>
                </table>
            </div>

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
