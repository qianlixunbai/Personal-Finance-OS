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
        <main className="auth-page">
            <section className="auth-card">
                <p className="auth-eyebrow">Personal Finance OS</p>
                <h2>注册</h2>
                <p className="auth-copy">建立账户后，即可在统一的财务视图中追踪资产、收支与净值。</p>
                {error && <div className="alert alert--error" role="alert">{error}</div>}
                <form onSubmit={submit} className="auth-form">
                    <label className="field">
                        <span className="field__label">用户名</span>
                        <input className="field__control" value={form.username} onChange={e => setForm({ ...form, username: e.target.value })} required />
                    </label>
                    <label className="field">
                        <span className="field__label">邮箱</span>
                        <input className="field__control" type="email" value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} required />
                    </label>
                    <label className="field">
                        <span className="field__label">密码</span>
                        <input className="field__control" type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} required />
                    </label>
                    <button type="submit" className="button button--primary">注册</button>
                </form>
                <p className="auth-footer">
                    <Link to="/login">已有账号？返回登录</Link>
                </p>
            </section>
        </main>
    );
}
