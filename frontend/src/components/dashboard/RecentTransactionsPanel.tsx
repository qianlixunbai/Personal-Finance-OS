import { Link } from 'react-router-dom';
import { Badge, Icon, type IconName } from '../Visual';
import { EmptyState } from '../Feedback';
import { formatTransactionAmount, formatTransactionType } from '../../utils/format';

export interface RecentTransaction {
    id: number;
    type: string;
    amount: number;
    category: string;
    account: string;
    date: string;
}

export function RecentTransactionsPanel({ transactions }: { transactions: RecentTransaction[] }) {
    const mobileTransactions = transactions.slice(0, 4);
    return <section className="ledger-panel" aria-labelledby="recent-transactions-heading">
        <header className="ledger-panel__header">
            <div><p className="dashboard-panel__eyebrow">最近活动</p><h2 id="recent-transactions-heading">最近交易</h2></div>
            <Link to="/transactions" className="ledger-panel__link">查看全部 <span aria-hidden="true">→</span></Link>
        </header>
        {transactions.length === 0 ? <EmptyState compact message="暂无最近流水，记录一笔交易后会显示在这里。" action={<Link to="/transactions" className="button button--secondary button--small">前往交易流水</Link>} /> : <>
            <div className="ledger-table"><table><thead><tr><th>分类与时间</th><th>账户</th><th>类型</th><th className="cell-number">金额</th></tr></thead><tbody>{transactions.map(transaction => <TransactionTableRow key={transaction.id} transaction={transaction} />)}</tbody></table></div>
            <ol className="ledger-mobile-list">{mobileTransactions.map(transaction => <TransactionMobileRow key={transaction.id} transaction={transaction} />)}</ol>
        </>}
    </section>;
}

function TransactionTableRow({ transaction }: { transaction: RecentTransaction }) {
    return <tr><td><TransactionIdentity transaction={transaction} /></td><td><span className="ledger-account">{transaction.account}</span></td><td><TransactionType type={transaction.type} /></td><td className="cell-number"><Amount type={transaction.type} value={transaction.amount} /></td></tr>;
}

function TransactionMobileRow({ transaction }: { transaction: RecentTransaction }) {
    return <li><TransactionIdentity transaction={transaction} /><div className="ledger-mobile-list__amount"><Amount type={transaction.type} value={transaction.amount} /><span>{transaction.account} · {formatTransactionType(transaction.type)}</span></div></li>;
}

function TransactionIdentity({ transaction }: { transaction: RecentTransaction }) {
    return <div className="ledger-identity"><span className={`category-glyph category-glyph--${transaction.type.toLowerCase()}`}><Icon name={iconForType(transaction.type)} size={16} /></span><span><strong>{transaction.category || '未分类'}</strong><small>{transaction.date?.replace('T', ' ') || '—'}</small></span></div>;
}

function TransactionType({ type }: { type: string }) {
    const tone = type === 'INCOME' ? 'success' : type === 'EXPENSE' ? 'danger' : 'primary';
    return <Badge tone={tone}>{formatTransactionType(type)}</Badge>;
}

function Amount({ type, value }: { type: string; value: number }) {
    const tone = type === 'INCOME' ? 'positive' : type === 'EXPENSE' ? 'negative' : 'neutral';
    return <span className={`amount amount--${tone}`}>{formatTransactionAmount(type, value)}</span>;
}

function iconForType(type: string): IconName {
    if (type === 'INCOME') return 'income';
    if (type === 'EXPENSE') return 'expense';
    return 'balance';
}
