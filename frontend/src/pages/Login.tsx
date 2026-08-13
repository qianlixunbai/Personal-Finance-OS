import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import api from '../api';
import { getErrorMessage } from '../utils/error';

export default function Login() {
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [error, setError] = useState('');
    const navigate = useNavigate();

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            const res = await api.post('/login', { username, password });
            localStorage.setItem('token', res.data.data.token);
            const userId = res.data.data.userId;
            if (!Number.isInteger(userId) || userId <= 0) throw new Error('登录响应缺少可信用户标识。');
            localStorage.setItem('finance-os:auth-user-id:v1', String(userId));
            navigate('/investments');
        } catch (err) {
            setError(getErrorMessage(err, '用户名或密码错误'));
        }
    };

    return (
        <main className="auth-page">
            <section className="auth-card">
                <p className="auth-eyebrow">Personal Finance OS</p>
                <h2>登录</h2>
                <p className="auth-copy">在一个清晰的财务视图中管理账户、资产与交易流水。</p>
                {error && <div className="alert alert--error" role="alert">{error}</div>}
                <form onSubmit={submit} className="auth-form">
                    <label className="field">
                        <span className="field__label">用户名</span>
                        <input className="field__control" value={username} onChange={e => setUsername(e.target.value)} required />
                    </label>
                    <label className="field">
                        <span className="field__label">密码</span>
                        <input className="field__control" type="password" value={password} onChange={e => setPassword(e.target.value)} required />
                    </label>
                    <button type="submit" className="button button--primary">登录</button>
                </form>
                <p className="auth-footer">
                    <Link to="/register">还没有账号？立即注册</Link>
                </p>
            </section>
        </main>
    );
}
