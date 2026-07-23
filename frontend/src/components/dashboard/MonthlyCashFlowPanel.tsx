import { formatCurrency } from '../../utils/format';

interface MonthlyCashFlowPanelProps {
    income: number;
    expense: number;
    net: number;
}

export function MonthlyCashFlowPanel({ income, expense, net }: MonthlyCashFlowPanelProps) {
    const hasIncome = income > 0;
    const balanceRate = hasIncome ? net / income : null;
    const balanceRatePercentage = balanceRate === null ? null : Math.round(balanceRate * 1000) / 10;
    const balanceRateWidth = balanceRate === null ? 0 : Math.min(Math.abs(balanceRate) * 100, 100);

    return <section className="cashflow-panel" aria-labelledby="cash-flow-heading">
        <div className="dashboard-panel__eyebrow"><span>本月现金流</span><span className={`dashboard-panel__status dashboard-panel__status--${net >= 0 ? 'positive' : 'negative'}`}>{net >= 0 ? '结余为正' : '结余为负'}</span></div>
        <h2 id="cash-flow-heading">收入、支出与结余</h2>
        <div className="cashflow-panel__metrics">
            <Metric label="收入" value={income} tone="positive" />
            <Metric label="支出" value={expense} tone="negative" />
            <Metric label="结余" value={net} tone={net >= 0 ? 'positive' : 'negative'} />
        </div>
        {balanceRatePercentage !== null ? <div className="cashflow-panel__balance-rate">
            <div><span>本月结余率</span><strong className={balanceRate! >= 0 ? 'amount amount--positive' : 'amount amount--negative'}>{balanceRatePercentage}%</strong></div>
            <div className={`cashflow-panel__ratio${balanceRate! < 0 ? ' cashflow-panel__ratio--negative' : ''}`} role="progressbar" aria-label={`本月结余率 ${balanceRatePercentage}%`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(balanceRateWidth)}><span style={{ width: `${balanceRateWidth}%` }} /></div>
        </div> : <p className="cashflow-panel__empty">本月收入为零，暂无结余率基准</p>}
    </section>;
}

function Metric({ label, value, tone }: { label: string; value: number; tone: 'positive' | 'negative' }) {
    return <div className="cashflow-metric"><span>{label}</span><strong className={`amount amount--${tone}`}>{formatCurrency(value)}</strong></div>;
}
