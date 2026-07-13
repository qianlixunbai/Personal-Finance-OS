import { Link, Outlet, useNavigate } from 'react-router-dom';

export default function Layout() {
    const navigate = useNavigate();

    const logout = () => {
        localStorage.removeItem('token');
        navigate('/login');
    };

    return (
        <div className="demo-shell">
            <nav className="app-nav" aria-label="主导航">
                <Link className="app-brand" to="/">Personal Finance OS <span>DEMO</span></Link>
                <div className="app-nav-links">
                    <Link to="/">仪表盘</Link>
                    <Link to="/accounts">账户</Link>
                    <Link to="/assets">资产</Link>
                    <Link to="/transactions">交易流水</Link>
                    <button type="button" onClick={logout}>返回介绍</button>
                </div>
            </nav>
            <main className="app-main">
                <p className="demo-notice" role="status">演示数据，仅用于项目展示；不代表真实账户，刷新页面将恢复初始示例。静态演示模式仅支持只读浏览，新增、编辑和删除功能请在完整本地版本中体验。</p>
                <Outlet />
            </main>
        </div>
    );
}
