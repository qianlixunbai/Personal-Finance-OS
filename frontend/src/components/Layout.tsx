import { Outlet, Link, useNavigate } from 'react-router-dom';

export default function Layout() {
    const navigate = useNavigate();
    const logout = () => {
        localStorage.removeItem('token');
        navigate('/login');
    };

    return (
        <div>
            <nav style={{ background: '#1a1a2e', color: '#fff', padding: '0 24px', height: 56, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 18, fontWeight: 700 }}>Personal Finance OS</span>
                <div style={{ display: 'flex', gap: 8 }}>
                    <Link to="/" style={{ color: '#ccc', textDecoration: 'none', padding: '8px 14px' }}>仪表盘</Link>
                    <Link to="/accounts" style={{ color: '#ccc', textDecoration: 'none', padding: '8px 14px' }}>账户</Link>
                    <Link to="/assets" style={{ color: '#ccc', textDecoration: 'none', padding: '8px 14px' }}>资产</Link>
                    <Link to="/transactions" style={{ color: '#ccc', textDecoration: 'none', padding: '8px 14px' }}>交易流水</Link>
                    <button onClick={logout} style={{ background: 'none', border: 'none', color: '#ccc', cursor: 'pointer', padding: '8px 14px', fontSize: 14 }}>退出</button>
                </div>
            </nav>
            <main style={{ maxWidth: 1100, margin: '0 auto', padding: 24 }}>
                <Outlet />
            </main>
        </div>
    );
}
