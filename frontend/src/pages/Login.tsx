import { useNavigate } from 'react-router-dom';

export default function Login() {
    const navigate = useNavigate();

    const enterDemo = () => {
        localStorage.setItem('token', 'demo-static-token');
        navigate('/');
    };

    return (
        <main className="demo-landing">
            <section className="demo-hero">
                <p className="demo-eyebrow">PORTFOLIO DEMO · V1.2</p>
                <h1>Personal Finance OS</h1>
                <p className="demo-lede">一个面向个人财务管理的全栈作品集：以账户、资产和交易流水为核心，在 Dashboard 中聚合查看财务概览与趋势。</p>
                <div className="demo-actions">
                    <button type="button" className="primary-action" onClick={enterDemo}>进入静态演示</button>
                    <a className="secondary-action" href="https://github.com/qianlixunbai/Personal-Finance-OS" target="_blank" rel="noreferrer">查看 GitHub 仓库</a>
                </div>
                <p className="demo-disclaimer">无需账号、后端、数据库或环境变量。所有页面均使用本地示例数据。</p>
            </section>

            <section className="demo-overview" aria-label="项目亮点">
                <article>
                    <span>01</span>
                    <h2>业务模块</h2>
                    <p>账户管理、资产持仓、分类与交易流水，以及聚合分析 Dashboard。</p>
                </article>
                <article>
                    <span>02</span>
                    <h2>工程化实现</h2>
                    <p>Java 21、Spring Boot 3、React、TypeScript、Vite、JWT 与 REST API。</p>
                </article>
                <article>
                    <span>03</span>
                    <h2>展示内容</h2>
                    <p>资产分布、当月收支、六个月趋势及可体验的页面交互。</p>
                </article>
            </section>

            <section className="demo-flow">
                <div>
                    <p className="demo-eyebrow">DEMO FLOW</p>
                    <h2>从概览到明细，快速了解已完成的 v1.2。</h2>
                </div>
                <ol>
                    <li>进入演示</li>
                    <li>查看 Dashboard 可视化</li>
                    <li>浏览账户、资产和交易流水</li>
                </ol>
            </section>
        </main>
    );
}
