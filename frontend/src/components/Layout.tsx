import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { Icon } from './Visual';

const links = [
    { to: '/', label: '仪表盘', end: true },
    { to: '/accounts', label: '账户' },
    { to: '/assets', label: '资产' },
    { to: '/transactions', label: '交易流水' },
];

export default function Layout() {
    const navigate = useNavigate();

    const logout = () => {
        localStorage.removeItem('token');
        navigate('/login');
    };

    return (
        <div className="app-shell">
            <nav className="app-nav" aria-label="主导航">
                <div className="app-nav__inner">
                    <NavLink to="/" end className="brand" aria-label="Personal Finance OS 仪表盘"><span className="brand__mark"><Icon name="brand" size={20} /></span><span>Personal Finance OS</span><span className="brand__tag">DEMO</span></NavLink>
                    <div className="app-nav__links">
                        {links.map(link => <NavLink key={link.to} to={link.to} end={link.end} className={({ isActive }) => `app-nav__link${isActive ? ' app-nav__link--active' : ''}`}>{link.label}</NavLink>)}
                        <button type="button" className="app-nav__logout" onClick={logout}>返回介绍</button>
                    </div>
                </div>
            </nav>
            <main className="app-main">
                <p className="demo-notice" role="status">演示数据，仅用于项目展示；不代表真实账户，刷新页面将恢复初始示例。静态演示模式仅支持只读浏览，新增、编辑和删除功能请在完整本地版本中体验。</p>
                <Outlet />
            </main>
        </div>
    );
}
