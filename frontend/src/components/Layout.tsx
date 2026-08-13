import { useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Icon, type IconName } from './Visual';

const links = [
    { to: '/', label: '仪表盘', icon: 'activity', end: true },
    { to: '/accounts', label: '账户', icon: 'accounts', end: false },
    { to: '/assets', label: '资产', icon: 'assets', end: false },
    { to: '/investments', label: '投资', icon: 'investments', end: false },
    { to: '/transactions', label: '交易流水', icon: 'transactions', end: false },
] as const satisfies readonly { to: string; label: string; icon: IconName; end?: boolean }[];

function formatLoadedAt(value: Date) {
    return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(value);
}

function SidebarNav({ onLogout }: { onLogout: () => void }) {
    return <aside className="app-sidebar">
        <NavLink to="/" end className="sidebar-brand" aria-label="Personal Finance OS 仪表盘" title="Personal Finance OS">
            <Icon name="brand" size={22} />
            <span className="sidebar-tooltip" role="tooltip">Personal Finance OS</span>
        </NavLink>
        <nav className="sidebar-nav" aria-label="主导航">
            {links.map(link => <NavLink key={link.to} to={link.to} end={link.end} title={link.label} aria-label={link.label} className={({ isActive }) => `sidebar-nav__link${isActive ? ' sidebar-nav__link--active' : ''}`}>
                <Icon name={link.icon} size={20} />
                <span className="sidebar-tooltip" role="tooltip">{link.label}</span>
            </NavLink>)}
        </nav>
        <button type="button" className="sidebar-nav__link sidebar-nav__logout" onClick={onLogout} aria-label="退出登录" title="退出登录">
            <Icon name="logout" size={20} />
            <span className="sidebar-tooltip" role="tooltip">退出登录</span>
        </button>
    </aside>;
}

function UserMenu({ onLogout }: { onLogout: () => void }) {
    return <details className="user-menu">
        <summary aria-label="用户菜单"><span className="user-menu__avatar"><Icon name="user" size={17} /></span><span className="user-menu__label">账户</span></summary>
        <div className="user-menu__popover"><button type="button" onClick={onLogout}><Icon name="logout" size={16} />退出登录</button></div>
    </details>;
}

function UtilityBar({ loadedAt, onLogout, pageName }: { loadedAt: Date; onLogout: () => void; pageName: string }) {
    return <header className="app-utility">
        <div className="utility-context"><span>{pageName}</span><div className="utility-status"><span className="utility-status__dot" />系统就绪</div></div>
        <div className="utility-meta"><span><Icon name="clock" size={14} />本次客户端载入 {formatLoadedAt(loadedAt)}</span><UserMenu onLogout={onLogout} /></div>
    </header>;
}

function MobileNavigation({ loadedAt, onLogout }: { loadedAt: Date; onLogout: () => void }) {
    return <>
        <header className="mobile-topbar">
            <NavLink to="/" end className="mobile-topbar__brand" aria-label="Personal Finance OS 仪表盘"><Icon name="brand" size={19} /><span>Finance OS</span></NavLink>
            <div className="mobile-topbar__actions"><span className="utility-status" aria-label="系统就绪"><span className="utility-status__dot" /></span><span className="mobile-topbar__loaded-at"><Icon name="clock" size={13} />{formatLoadedAt(loadedAt)}</span><UserMenu onLogout={onLogout} /></div>
        </header>
        <nav className="mobile-bottom-nav" aria-label="移动端主导航">
            {links.map(link => <NavLink key={link.to} to={link.to} end={link.end} aria-label={link.label} className={({ isActive }) => `mobile-bottom-nav__link${isActive ? ' mobile-bottom-nav__link--active' : ''}`}><Icon name={link.icon} size={19} /><span>{link.label}</span></NavLink>)}
        </nav>
    </>;
}

export default function Layout() {
    const navigate = useNavigate();
    const location = useLocation();
    const [loadedAt] = useState(() => new Date());
    const logout = () => {
        const unresolved = Object.keys(localStorage).some(key => key.startsWith('finance-os:investment-command:pending:v1:'));
        if (unresolved && !window.confirm('仍有未完成的投资命令。退出不会删除恢复记录，重新登录后仍需继续处理。确认退出吗？')) return;
        localStorage.removeItem('token'); localStorage.removeItem('finance-os:auth-user-id:v1'); navigate('/login');
    };
    const pageName = links.find(link => link.to === location.pathname)?.label ?? '财务工作区';

    return <div className="app-shell">
        <SidebarNav onLogout={logout} />
        <UtilityBar loadedAt={loadedAt} onLogout={logout} pageName={pageName} />
        <MobileNavigation loadedAt={loadedAt} onLogout={logout} />
        <main className="app-main"><Outlet /></main>
    </div>;
}
