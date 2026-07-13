import { useEffect, useState } from 'react';
import type { CSSProperties, FormEvent } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { getErrorMessage } from '../utils/error';

interface Account {
    id: number;
    name: string;
    type: string;
    currency: string;
    balance: number;
    status: string;
    createdAt: string;
}

interface PageResult<T> {
    records: T[];
    total: number;
    page: number;
    size: number;
}

const pageSize = 20;
const emptyForm = () => ({ name: '', type: 'BANK', currency: 'CNY' });

export default function Accounts() {
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [showForm, setShowForm] = useState(false);
    const [editingId, setEditingId] = useState<number | null>(null);
    const [form, setForm] = useState(emptyForm);
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');

    const fetch = async (targetPage = page) => {
        const res = await api.get('/accounts/page', { params: { page: targetPage, size: pageSize } });
        const data: PageResult<Account> = res.data.data;
        const records = data.records || [];

        if (records.length === 0 && targetPage > 1 && (data.total || 0) > 0) {
            await fetch(targetPage - 1);
            return;
        }

        setAccounts(records);
        setTotal(data.total || 0);
        setPage(data.page || targetPage);
    };

    useEffect(() => {
        fetch(1).catch(err => setError(getErrorMessage(err, '账户列表加载失败')));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const openCreateForm = () => {
        setEditingId(null);
        setForm(emptyForm());
        setShowForm(true);
        setError('');
        setSuccess('');
    };

    const openEditForm = (account: Account) => {
        setEditingId(account.id);
        setForm({
            name: account.name,
            type: account.type,
            currency: account.currency || 'CNY',
        });
        setShowForm(true);
        setError('');
        setSuccess('');
    };

    const closeForm = () => {
        setEditingId(null);
        setForm(emptyForm());
        setShowForm(false);
    };

    const submit = async (e: FormEvent) => {
        e.preventDefault();
        setError('');
        setSuccess('');

        try {
            if (editingId) {
                await api.put(`/accounts/${editingId}`, form);
                setSuccess('账户已更新');
            } else {
                await api.post('/accounts', form);
                setSuccess('账户已创建');
            }
            closeForm();
            await fetch(page);
        } catch (err) {
            setError(getErrorMessage(err, '账户保存失败'));
        }
    };

    const deactivate = async (id: number) => {
        if (confirm('确定停用该账户？')) {
            try {
                await api.post(`/accounts/${id}/deactivate`);
                setSuccess('账户已停用');
                setError('');
                await fetch(page);
            } catch (err) {
                setError(getErrorMessage(err, '账户停用失败'));
            }
        }
    };

    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    const hasNextPage = page < totalPages && accounts.length >= pageSize;

    return (
        <div>
            <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>账户管理</h2>
                <button onClick={showForm ? closeForm : openCreateForm} style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>
                    {showForm ? '收起表单' : '新增账户'}
                </button>
            </div>

            {error && <AlertMessage type="error">{error}</AlertMessage>}
            {success && <AlertMessage type="success">{success}</AlertMessage>}

            {showForm && (
                <form onSubmit={submit} style={{ background: '#fff', padding: 24, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <h3 style={{ marginTop: 0, marginBottom: 16 }}>{editingId ? '编辑账户' : '新增账户'}</h3>
                    <div style={{ marginBottom: 16 }}>
                        <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>名称</label>
                        <input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8 }} />
                    </div>
                    <div style={{ marginBottom: 16 }}>
                        <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>类型</label>
                        <select value={form.type} onChange={e => setForm({ ...form, type: e.target.value })} style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8 }}>
                            <option value="CASH">现金</option>
                            <option value="BANK">银行</option>
                            <option value="CREDIT_CARD">信用卡</option>
                            <option value="PAYMENT_PLATFORM">支付平台</option>
                            <option value="BROKERAGE">券商</option>
                            <option value="CRYPTO_WALLET">加密钱包</option>
                        </select>
                    </div>
                    <div style={{ marginBottom: 16 }}>
                        <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>币种</label>
                        <input value={form.currency} onChange={e => setForm({ ...form, currency: e.target.value })} style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8 }} />
                    </div>
                    <div style={{ display: 'flex', gap: 8 }}>
                        <button type="submit" style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>{editingId ? '保存修改' : '保存'}</button>
                        {editingId && <button type="button" onClick={closeForm} style={{ padding: '10px 20px', background: '#636e72', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>取消编辑</button>}
                    </div>
                </form>
            )}

            <div className="table-scroll">
            <table style={{ width: '100%', borderCollapse: 'collapse', background: '#fff', borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                <thead>
                    <tr>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>名称</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>类型</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>余额</th>
                        <th style={{ padding: '12px 16px', textAlign: 'left', borderBottom: '1px solid #eee' }}>操作</th>
                    </tr>
                </thead>
                <tbody>
                    {accounts.map(a => (
                        <tr key={a.id}>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{a.name}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>{a.type}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee', fontWeight: 600, color: '#00b894' }}>¥{a.balance?.toFixed(2)}</td>
                            <td style={{ padding: '12px 16px', borderBottom: '1px solid #eee' }}>
                                <div style={{ display: 'flex', gap: 8 }}>
                                    <button onClick={() => openEditForm(a)} style={{ padding: '6px 12px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>编辑</button>
                                    {a.status === 'ACTIVE' && <button onClick={() => deactivate(a.id)} style={{ padding: '6px 12px', background: '#e17055', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>停用</button>}
                                </div>
                            </td>
                        </tr>
                    ))}
                    {accounts.length === 0 && (
                        <EmptyTableRow colSpan={4} message="暂无账户" />
                    )}
                </tbody>
            </table>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 16 }}>
                <span style={{ color: '#636e72' }}>第 {page} / {totalPages} 页，共 {total} 个账户</span>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={() => fetch(page - 1)} disabled={page <= 1} style={pageButtonStyle}>上一页</button>
                    <button onClick={() => fetch(page + 1)} disabled={!hasNextPage} style={pageButtonStyle}>下一页</button>
                </div>
            </div>
        </div>
    );
}

const pageButtonStyle: CSSProperties = {
    padding: '8px 14px',
    background: '#fff',
    border: '1px solid #ddd',
    borderRadius: 8,
    cursor: 'pointer',
};
