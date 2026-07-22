import { useNavigate } from 'react-router-dom';

export default function Login() {
    const navigate = useNavigate();
    const enterDemo = () => { localStorage.setItem('token', 'demo-static-token'); navigate('/'); };
    return <main className="demo-landing">
        <section className="demo-hero">
            <p className="demo-eyebrow">PORTFOLIO DEMO · READ-ONLY</p>
            <h1>Personal Finance OS</h1>
            <p className="demo-lede">一个面向个人财务管理的全栈作品集：以账户、资产和交易流水为核心，在 Dashboard 中聚合查看财务概览与趋势。</p>
            <div className="demo-actions"><button type="button" className="primary-action" onClick={enterDemo}>进入静态演示</button><a className="secondary-action" href="https://github.com/qianlixunbai/Personal-Finance-OS" target="_blank" rel="noreferrer">查看 GitHub 仓库</a></div>
            <p className="demo-disclaimer">当前公开站点是静态只读 Demo，不需要注册、登录、后端、数据库或环境变量。演示数据全部为虚构数据，不代表真实账户。</p>
        </section>
        <section className="demo-overview" aria-label="项目亮点">
            <article><span>01</span><h2>业务模块</h2><p>账户管理、资产持仓、分类与交易流水，以及聚合分析 Dashboard。</p></article>
            <article><span>02</span><h2>技术实现</h2><p>Java 21、Spring Boot 3、PostgreSQL、React、TypeScript、Vite、JWT 与 REST API。</p></article>
            <article><span>03</span><h2>完整项目能力</h2><p>完整项目包含后端、权限、测试、容器化与部署能力；本站仅展示安全的只读体验。</p></article>
        </section>
        <section className="demo-flow"><div><p className="demo-eyebrow">DEMO FLOW</p><h2>从概览到明细，快速体验项目的 Dashboard、账户、资产与交易流水页面。</h2></div><ol><li>进入演示</li><li>查看 Dashboard 可视化</li><li>浏览账户、资产和交易流水</li></ol></section>
    </main>;
}
