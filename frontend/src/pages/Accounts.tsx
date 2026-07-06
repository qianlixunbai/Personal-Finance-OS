import { useEffect, useState } from 'react';
import api from '../api';

interface Account {
    id: number;
    name: string;
    type: string;
    currency: string;
    balance: number;
    status: string;
    createdAt: string;
}

export default function Accounts() {
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [showForm, setShowForm] = useState(false);
    const [form, setForm] = useState({ name: '', type: 'BANK', currency: 'CNY' });

    const fetch = () => api.get('/accounts').then(res => setAccounts(res.data.data));
    useEffect(() => { fetch(); }, []);

    const create = async (e: React.FormEvent) => {
        e.preventDefault();
        await api.post('/accounts', form);
        setShowForm(false);
        fetch();
    };

    const deactivate = async (id: number) => {
        if (confirm('确定停用该账户？')) {
            await api.post(`/accounts/${id}/deactivate`);
            fetch();
        }
    };

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>账户管理</h2>
                <button onClick={() => setShowForm(!showForm)} style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>新增账户</button>
            </div>

            {showForm && (
                <form onSubmit={create} style={{ background: '#fff', padding: 24, borderRadius: 12, marginBottom: 20, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
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
                    <button type="submit" style={{ padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer' }}>保存</button>
                </form>
            )}

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
                                {a.status === 'ACTIVE' && <button onClick={() => deactivate(a.id)} style={{ padding: '6px 12px', background: '#e17055', color: '#fff', border: 'none', borderRadius: 6, cursor: 'pointer' }}>停用</button>}
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
