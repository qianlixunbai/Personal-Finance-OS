import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import api from '../api';
import { getErrorMessage } from '../utils/error';

export default function Register() {
    const [form, setForm] = useState({ username: '', email: '', password: '' });
    const [error, setError] = useState('');
    const navigate = useNavigate();

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            await api.post('/register', form);
            navigate('/login');
        } catch (err) {
            setError(getErrorMessage(err, '注册失败'));
        }
    };

    return (
        <div style={{ maxWidth: 400, margin: '80px auto', padding: 40, background: '#fff', borderRadius: 12, boxShadow: '0 2px 16px rgba(0,0,0,.08)' }}>
            <h2 style={{ textAlign: 'center', marginBottom: 24 }}>注册</h2>
            {error && <div style={{ background: '#f8d7da', color: '#721c24', padding: 10, borderRadius: 8, marginBottom: 16 }}>{error}</div>}
            <form onSubmit={submit}>
                <div style={{ marginBottom: 16 }}>
                    <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>用户名</label>
                    <input value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
                </div>
                <div style={{ marginBottom: 16 }}>
                    <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>邮箱</label>
                    <input type="email" value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
                </div>
                <div style={{ marginBottom: 16 }}>
                    <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>密码</label>
                    <input type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
                </div>
                <button type="submit" style={{ width: '100%', padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, fontSize: 15, cursor: 'pointer' }}>注册</button>
            </form>
            <p style={{ textAlign: 'center', marginTop: 16, fontSize: 14 }}>
                <Link to="/login">已有账号？返回登录</Link>
            </p>
        </div>
    );
}
