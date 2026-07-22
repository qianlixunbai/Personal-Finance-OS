import { useEffect, useMemo, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { PageHeader } from '../components/PageHeader';
import { Pagination } from '../components/Pagination';
import { Badge, Icon } from '../components/Visual';
import { getErrorMessage } from '../utils/error';
import { formatTransactionAmount, formatTransactionType } from '../utils/format';

type TransactionType = 'INCOME' | 'EXPENSE' | 'ADJUSTMENT';
interface Transaction { id: number; accountId: number; categoryId: number; type: TransactionType; amount: number; currency: string; description?: string; transactedAt: string; }
interface Account { id: number; name: string; }
interface Category { id: number; name: string; }
interface PageResult<T> { records: T[]; total: number; page: number; size: number; }
interface Filters { type: string; accountId: string; categoryId: string; start: string; end: string; }
const pageSize = 20; const typeOptions: TransactionType[] = ['INCOME', 'EXPENSE', 'ADJUSTMENT'];
const toLocalDateTime = (value: string) => value.length === 16 ? `${value}:00` : value;

export default function Transactions() {
    const [transactions, setTransactions] = useState<Transaction[]>([]); const [total, setTotal] = useState(0); const [page, setPage] = useState(1); const [accounts, setAccounts] = useState<Account[]>([]); const [categories, setCategories] = useState<Category[]>([]); const [filters, setFilters] = useState<Filters>({ type: '', accountId: '', categoryId: '', start: '', end: '' }); const [error, setError] = useState('');
    const accountNameById = useMemo(() => new Map(accounts.map(account => [account.id, account.name])), [accounts]); const categoryNameById = useMemo(() => new Map(categories.map(category => [category.id, category.name])), [categories]);
    const fetchTransactions = async (targetPage = page) => { const params: Record<string, string | number> = { page: targetPage, size: pageSize }; if (filters.type) params.type = filters.type; if (filters.accountId) params.accountId = filters.accountId; if (filters.categoryId) params.categoryId = filters.categoryId; if (filters.start) params.start = toLocalDateTime(filters.start); if (filters.end) params.end = toLocalDateTime(filters.end); const res = await api.get('/transactions/page', { params }); const data: PageResult<Transaction> = res.data.data; setTransactions(data.records || []); setTotal(data.total || 0); setPage(data.page || targetPage); };
    useEffect(() => {
        Promise.all([api.get('/accounts'), api.get('/categories')]).then(([accountRes, categoryRes]) => { setAccounts(accountRes.data.data || []); setCategories(categoryRes.data.data || []); }).catch(err => setError(getErrorMessage(err, '筛选条件加载失败')));
        api.get('/transactions/page', { params: { page: 1, size: pageSize } }).then(res => { const data: PageResult<Transaction> = res.data.data; setTransactions(data.records || []); setTotal(data.total || 0); setPage(data.page || 1); }).catch(err => setError(getErrorMessage(err, '交易记录加载失败')));
    }, []);
    const search = (event: FormEvent) => { event.preventDefault(); setError(''); fetchTransactions(1).catch(err => setError(getErrorMessage(err, '筛选失败'))); }; const totalPages = Math.max(1, Math.ceil(total / pageSize));
    return <div>
        <PageHeader title="交易流水" subtitle="可按类型、账户、分类和时间筛选的只读虚构交易记录" />
        {error && <AlertMessage type="error">{error}</AlertMessage>}
        <form onSubmit={search} className="page-panel"><div className="filter-grid"><Field label="类型"><select className="field__control" value={filters.type} onChange={event => setFilters({ ...filters, type: event.target.value })}><option value="">全部</option>{typeOptions.map(type => <option key={type} value={type}>{formatTransactionType(type)}</option>)}</select></Field><Field label="账户"><select className="field__control" value={filters.accountId} onChange={event => setFilters({ ...filters, accountId: event.target.value })}><option value="">全部</option>{accounts.map(account => <option key={account.id} value={account.id}>{account.name}</option>)}</select></Field><Field label="分类"><select className="field__control" value={filters.categoryId} onChange={event => setFilters({ ...filters, categoryId: event.target.value })}><option value="">全部</option>{categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select></Field><Field label="开始时间"><input className="field__control" type="datetime-local" value={filters.start} onChange={event => setFilters({ ...filters, start: event.target.value })} /></Field><Field label="结束时间"><input className="field__control" type="datetime-local" value={filters.end} onChange={event => setFilters({ ...filters, end: event.target.value })} /></Field><button type="submit" className="pagination__button">筛选</button></div></form>
        <section className="content-card"><h2 className="content-card__title"><span className="content-card__title-icon"><Icon name="transactions" size={17} /></span>交易记录</h2><div className="table-scroll"><table className="data-table"><thead><tr><th>时间</th><th>类型</th><th>账户</th><th>分类</th><th className="cell-number">金额</th><th>币种</th><th>说明</th></tr></thead><tbody>{transactions.map(transaction => <tr key={transaction.id}><td>{transaction.transactedAt.replace('T', ' ')}</td><td><Badge tone={transaction.type === 'INCOME' ? 'success' : transaction.type === 'EXPENSE' ? 'danger' : 'primary'}>{formatTransactionType(transaction.type)}</Badge></td><td>{accountNameById.get(transaction.accountId) || transaction.accountId}</td><td>{categoryNameById.get(transaction.categoryId) || transaction.categoryId}</td><td className="cell-number"><span className={`amount amount--${transaction.type === 'INCOME' ? 'positive' : transaction.type === 'EXPENSE' ? 'negative' : 'neutral'}`}>{formatTransactionAmount(transaction.type, transaction.amount)}</span></td><td>{transaction.currency}</td><td>{transaction.description || '-'}</td></tr>)}{transactions.length === 0 && <EmptyTableRow colSpan={7} message="暂无流水" />}</tbody></table></div><Pagination page={page} totalPages={totalPages} summary={<>共 {total} 条</>} previousDisabled={page <= 1} nextDisabled={page >= totalPages} onPrevious={() => fetchTransactions(page - 1)} onNext={() => fetchTransactions(page + 1)} /></section>
    </div>;
}
function Field({ label, children }: { label: string; children: ReactNode }) { return <label className="field"><span className="field__label">{label}</span>{children}</label>; }
