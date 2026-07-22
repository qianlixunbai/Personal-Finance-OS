import { useEffect, useState } from 'react';
import api from '../api';
import { EmptyTableRow, AlertMessage } from '../components/Feedback';
import { PageHeader } from '../components/PageHeader';
import { Pagination } from '../components/Pagination';
import { Badge, Icon, SummaryCard } from '../components/Visual';
import { getErrorMessage } from '../utils/error';
import { formatCurrency } from '../utils/format';

interface Account { id: number; name: string; type: string; currency: string; balance: number; status: string; }
interface PageResult<T> { records: T[]; total: number; page: number; size: number; }
const pageSize = 20;

export default function Accounts() {
    const [accounts, setAccounts] = useState<Account[]>([]); const [total, setTotal] = useState(0); const [page, setPage] = useState(1); const [error, setError] = useState('');
    const fetchPage = async (targetPage = page) => { const res = await api.get('/accounts/page', { params: { page: targetPage, size: pageSize } }); const data: PageResult<Account> = res.data.data; setAccounts(data.records || []); setTotal(data.total || 0); setPage(data.page || targetPage); };
    useEffect(() => { fetchPage(1).catch(err => setError(getErrorMessage(err, '账户列表加载失败'))); }, []);
    const totalPages = Math.max(1, Math.ceil(total / pageSize)); const activeCount = accounts.filter(account => account.status === 'ACTIVE').length; const typeCount = new Set(accounts.map(account => account.type)).size;
    return <div>
        <PageHeader title="账户管理" subtitle="展示虚构账户的类型、状态与余额；公开 Demo 仅支持查看" />
        <section className="summary-grid summary-grid--three" aria-label="账户概览"><SummaryCard icon="accounts" label="账户总数">{total}</SummaryCard><SummaryCard icon="shield" label="当前页活跃账户" tone="purple">{activeCount}</SummaryCard><SummaryCard icon="assets" label="当前页账户类型" tone="success">{typeCount}</SummaryCard></section>
        {error && <AlertMessage type="error">{error}</AlertMessage>}
        <section className="content-card"><h2 className="content-card__title"><span className="content-card__title-icon"><Icon name="accounts" size={17} /></span>账户列表</h2><div className="table-scroll"><table className="data-table"><thead><tr><th>名称</th><th>类型</th><th>币种</th><th>状态</th><th className="cell-number">余额</th></tr></thead><tbody>{accounts.map(account => <tr key={account.id}><td className="table-primary">{account.name}</td><td>{account.type}</td><td>{account.currency || 'CNY'}</td><td><Badge tone={account.status === 'ACTIVE' ? 'success' : 'warning'} dot>{account.status === 'ACTIVE' ? '正常' : account.status}</Badge></td><td className="cell-number"><span className={`amount amount--${account.balance >= 0 ? 'positive' : 'negative'}`}>{formatCurrency(account.balance)}</span></td></tr>)}{accounts.length === 0 && <EmptyTableRow colSpan={5} message="暂无账户" />}</tbody></table></div><Pagination page={page} totalPages={totalPages} summary={<>共 {total} 个账户</>} previousDisabled={page <= 1} nextDisabled={page >= totalPages} onPrevious={() => fetchPage(page - 1)} onNext={() => fetchPage(page + 1)} /></section>
    </div>;
}
