import { useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Icon, type IconName } from './Visual';

const links = [
    { to: '/', label: '仪表盘', icon: 'activity', end: true },
    { to: '/accounts', label: '账户', icon: 'accounts', end: false },
    { to: '/assets', label: '资产', icon: 'assets', end: false },
    { to: '/transactions', label: '交易流水', icon: 'transactions', end: false },
] as const satisfies readonly { to: string; label: string; icon: IconName; end?: boolean }[];

function formatLoadedAt(value: Date) {
    return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(value);
}

function SidebarNav({ onExit }: { onExit: () => void }) {
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
        <button type="button" className="sidebar-nav__link sidebar-nav__logout" onClick={onExit} aria-label="返回介绍" title="返回介绍">
            <Icon name="logout" size={20} />
            <span className="sidebar-tooltip" role="tooltip">返回介绍</span>
        </button>
    </aside>;
}

function UserMenu({ onExit }: { onExit: () => void }) {
    return <details className="user-menu">
        <summary aria-label="用户菜单"><span className="user-menu__avatar"><Icon name="user" size={17} /></span><span className="user-menu__label">账户</span></summary>
        <div className="user-menu__popover"><button type="button" onClick={onExit}><Icon name="logout" size={16} />返回介绍</button></div>
    </details>;
}

function UtilityBar({ loadedAt, onExit, pageName }: { loadedAt: Date; onExit: () => void; pageName: string }) {
    return <header className="app-utility">
        <div className="utility-context"><span>{pageName}</span><div className="utility-status"><span className="utility-status__dot" />只读演示</div></div>
        <div className="utility-meta"><span><Icon name="clock" size={14} />本次客户端载入 {formatLoadedAt(loadedAt)}</span><UserMenu onExit={onExit} /></div>
    </header>;
}

function MobileNavigation({ loadedAt, onExit }: { loadedAt: Date; onExit: () => void }) {
    return <>
        <header className="mobile-topbar">
            <NavLink to="/" end className="mobile-topbar__brand" aria-label="Personal Finance OS 仪表盘"><Icon name="brand" size={19} /><span>Finance OS</span></NavLink>
            <div className="mobile-topbar__actions"><span className="utility-status" aria-label="只读演示"><span className="utility-status__dot" /></span><span className="mobile-topbar__loaded-at"><Icon name="clock" size={13} />{formatLoadedAt(loadedAt)}</span><UserMenu onExit={onExit} /></div>
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
    const exitDemo = () => { localStorage.removeItem('token'); navigate('/login'); };
    const pageName = links.find(link => link.to === location.pathname)?.label ?? '财务工作区';

    return <div className="app-shell">
        <SidebarNav onExit={exitDemo} />
        <UtilityBar loadedAt={loadedAt} onExit={exitDemo} pageName={pageName} />
        <MobileNavigation loadedAt={loadedAt} onExit={exitDemo} />
        <main className="app-main"><p className="demo-notice" role="status">演示数据，仅用于项目展示；不代表真实账户。静态演示模式仅支持只读浏览，新增、编辑和删除功能请在完整本地版本中体验。</p><Outlet /></main>
    </div>;
}
