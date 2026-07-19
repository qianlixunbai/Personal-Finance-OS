import { useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { PageHeader } from '../components/PageHeader';
import { Pagination } from '../components/Pagination';
import { Badge, Icon, SummaryCard } from '../components/Visual';
import type { PageResult } from '../types/pagination';
import { getErrorMessage } from '../utils/error';
import { formatCurrency } from '../utils/format';

interface Account { id: number; name: string; type: string; currency: string; balance: number; status: string; createdAt: string; }
const pageSize = 20;
const emptyForm = () => ({ name: '', type: 'BANK', currency: 'CNY' });

export default function Accounts() {
    const [accounts, setAccounts] = useState<Account[]>([]); const [total, setTotal] = useState(0); const [page, setPage] = useState(1);
    const [showForm, setShowForm] = useState(false); const [editingId, setEditingId] = useState<number | null>(null); const [form, setForm] = useState(emptyForm);
    const [error, setError] = useState(''); const [success, setSuccess] = useState('');
    const fetch = async (targetPage = page) => {
        const res = await api.get('/accounts/page', { params: { page: targetPage, size: pageSize } }); const data: PageResult<Account> = res.data.data; const records = data.records || [];
        if (records.length === 0 && targetPage > 1 && (data.total || 0) > 0) { await fetch(targetPage - 1); return; }
        setAccounts(records); setTotal(data.total || 0); setPage(data.page || targetPage);
    };
    useEffect(() => { fetch(1).catch(err => setError(getErrorMessage(err, '账户列表加载失败'))); // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    const openCreateForm = () => { setEditingId(null); setForm(emptyForm()); setShowForm(true); setError(''); setSuccess(''); };
    const openEditForm = (account: Account) => { setEditingId(account.id); setForm({ name: account.name, type: account.type, currency: account.currency || 'CNY' }); setShowForm(true); setError(''); setSuccess(''); };
    const closeForm = () => { setEditingId(null); setForm(emptyForm()); setShowForm(false); };
    const submit = async (event: FormEvent) => { event.preventDefault(); setError(''); setSuccess(''); try { if (editingId) { await api.put(`/accounts/${editingId}`, form); setSuccess('账户已更新'); } else { await api.post('/accounts', form); setSuccess('账户已创建'); } closeForm(); await fetch(page); } catch (err) { setError(getErrorMessage(err, '账户保存失败')); } };
    const deactivate = async (id: number) => { if (!confirm('确定停用该账户？')) return; try { await api.post(`/accounts/${id}/deactivate`); setSuccess('账户已停用'); setError(''); await fetch(page); } catch (err) { setError(getErrorMessage(err, '账户停用失败')); } };
    const totalPages = Math.max(1, Math.ceil(total / pageSize)); const hasNextPage = page < totalPages && accounts.length >= pageSize;
    const activeCount = accounts.filter(account => account.status === 'ACTIVE').length; const typeCount = new Set(accounts.map(account => account.type)).size;
    return <div>
        <PageHeader title="账户管理" subtitle="管理您的所有账户，查看账户状态与余额概况" actions={<button type="button" className="button button--primary" onClick={showForm ? closeForm : openCreateForm}>{showForm ? '收起表单' : '新增账户'}</button>} />
        <section className="summary-grid summary-grid--three" aria-label="账户概览">
            <SummaryCard icon="accounts" label="账户总数">{total}</SummaryCard><SummaryCard icon="shield" label="当前页活跃账户" tone="purple">{activeCount}</SummaryCard><SummaryCard icon="assets" label="当前页账户类型" tone="success">{typeCount}</SummaryCard>
        </section>
        {error && <AlertMessage type="error">{error}</AlertMessage>}{success && <AlertMessage type="success">{success}</AlertMessage>}
        {showForm && <form onSubmit={submit} className="page-panel"><h2 className="content-card__title">{editingId ? '编辑账户' : '新增账户'}</h2><div className="form-grid"><Field label="名称"><input className="field__control" value={form.name} onChange={event => setForm({ ...form, name: event.target.value })} required /></Field><Field label="类型"><select className="field__control" value={form.type} onChange={event => setForm({ ...form, type: event.target.value })}><option value="CASH">现金</option><option value="BANK">银行</option><option value="CREDIT_CARD">信用卡</option><option value="PAYMENT_PLATFORM">支付平台</option><option value="BROKERAGE">券商</option><option value="CRYPTO_WALLET">加密钱包</option></select></Field><Field label="币种"><input className="field__control" value={form.currency} onChange={event => setForm({ ...form, currency: event.target.value })} /></Field></div><div className="data-table__actions" style={{ marginTop: 16 }}><button type="submit" className="button button--primary">{editingId ? '保存修改' : '保存'}</button>{editingId && <button type="button" className="button button--neutral" onClick={closeForm}>取消编辑</button>}</div></form>}
        <section className="content-card"><h2 className="content-card__title"><span className="content-card__title-icon"><Icon name="accounts" size={17} /></span>账户列表</h2><div className="table-scroll"><table className="data-table"><thead><tr><th>账户名称</th><th>类型</th><th>状态</th><th className="cell-number">余额</th><th>操作</th></tr></thead><tbody>{accounts.map(account => <tr key={account.id}><td className="table-primary">{account.name}</td><td>{account.type}</td><td><Badge tone={account.status === 'ACTIVE' ? 'success' : 'warning'} dot>{account.status}</Badge></td><td className="cell-number"><span className={`amount amount--${account.balance < 0 ? 'negative' : 'positive'}`}>{formatCurrency(account.balance)}</span></td><td className="cell-actions"><div className="data-table__actions"><button type="button" className="button button--secondary button--small" onClick={() => openEditForm(account)}>编辑</button>{account.status === 'ACTIVE' && <button type="button" className="button button--danger button--small" onClick={() => deactivate(account.id)}>停用</button>}</div></td></tr>)}{accounts.length === 0 && <EmptyTableRow colSpan={5} message="暂无账户" />}</tbody></table></div><Pagination page={page} totalPages={totalPages} summary={<>共 {total} 个账户</>} previousDisabled={page <= 1} nextDisabled={!hasNextPage} onPrevious={() => fetch(page - 1)} onNext={() => fetch(page + 1)} /></section>
    </div>;
}
function Field({ label, children }: { label: string; children: ReactNode }) { return <label className="field"><span className="field__label">{label}</span>{children}</label>; }
