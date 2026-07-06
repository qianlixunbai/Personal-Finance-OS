import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import api from '../api';

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
            navigate('/');
        } catch {
            setError('用户名或密码错误');
        }
    };

    return (
        <div style={{ maxWidth: 400, margin: '80px auto', padding: 40, background: '#fff', borderRadius: 12, boxShadow: '0 2px 16px rgba(0,0,0,.08)' }}>
            <h2 style={{ textAlign: 'center', marginBottom: 24 }}>登录</h2>
            {error && <div style={{ background: '#f8d7da', color: '#721c24', padding: 10, borderRadius: 8, marginBottom: 16 }}>{error}</div>}
            <form onSubmit={submit}>
                <div style={{ marginBottom: 16 }}>
                    <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>用户名</label>
                    <input value={username} onChange={e => setUsername(e.target.value)} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
                </div>
                <div style={{ marginBottom: 16 }}>
                    <label style={{ display: 'block', marginBottom: 6, fontWeight: 600 }}>密码</label>
                    <input type="password" value={password} onChange={e => setPassword(e.target.value)} required style={{ width: '100%', padding: '10px 12px', border: '1px solid #ddd', borderRadius: 8, fontSize: 15 }} />
                </div>
                <button type="submit" style={{ width: '100%', padding: '10px 20px', background: '#6c5ce7', color: '#fff', border: 'none', borderRadius: 8, fontSize: 15, cursor: 'pointer' }}>登录</button>
            </form>
            <p style={{ textAlign: 'center', marginTop: 16, fontSize: 14 }}>
                <Link to="/register">还没有账号？立即注册</Link>
            </p>
        </div>
    );
}
