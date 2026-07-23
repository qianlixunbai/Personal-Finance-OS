import { Icon } from '../Visual';
import { formatCurrency } from '../../utils/format';

interface NetWorthHeroProps {
    totalAssets: number;
    netWorth: number;
}

export function NetWorthHero({ totalAssets, netWorth }: NetWorthHeroProps) {
    return <section className="dashboard-hero" aria-labelledby="net-worth-heading">
        <div className="dashboard-panel__eyebrow"><span><Icon name="activity" size={15} />当前账务汇总</span><span className="dashboard-panel__status">CNY</span></div>
        <h1 id="net-worth-heading">净资产</h1>
        <p className="dashboard-hero__value">{formatCurrency(netWorth)}</p>
        <div className="dashboard-hero__foot">
            <div className="dashboard-hero__support"><span>总资产</span><strong>{formatCurrency(totalAssets)}</strong></div>
            <span className="dashboard-hero__baseline">暂无历史净资产比较基准</span>
        </div>
    </section>;
}
