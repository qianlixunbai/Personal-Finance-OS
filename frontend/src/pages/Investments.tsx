import { useEffect, useRef, useState } from 'react';
import {
    fetchInvestmentAuditTimeline, fetchInvestmentPortfolio, fetchInvestmentPosition,
    fetchInvestmentPositions, fetchInvestmentTransaction, fetchInvestmentTransactions,
} from '../api/investment';
import { AlertMessage, EmptyState, EmptyTableRow } from '../components/Feedback';
import { PageHeader } from '../components/PageHeader';
import { Badge, SummaryCard } from '../components/Visual';
import type {
    CursorPage, InvestmentAuditTimeline, InvestmentBusinessValues, InvestmentCorrectionStatus,
    InvestmentCurrentPosition, InvestmentPortfolio, InvestmentPositionDetail, InvestmentPositionListItem,
    InvestmentPositionStatus, InvestmentReceipt, InvestmentTransactionDetail, InvestmentTransactionListItem,
    InvestmentTransactionType,
} from '../types/investment';
import { createCursorPageState, createLatestRequestGate, goToNextCursorPage, goToPreviousCursorPage, resetCursorPageState, type CursorPageState } from '../utils/investmentCursor';
import {
    formatInvestmentDateTime, formatInvestmentMoney, formatInvestmentQuantity, getInvestmentReadErrorMessage,
    investmentAuditEventLabel, investmentAuditFactRoleLabel, investmentCorrectionStatusLabel, investmentFreshnessLabel,
    investmentReceiptApplicabilityLabel, investmentTransactionTypeLabel, investmentWarningMessages,
} from '../utils/investmentRead';

const PAGE_SIZE = 20;
const emptyPositionPage: CursorPage<InvestmentPositionListItem> = { records: [], nextCursor: null, hasMore: false, size: PAGE_SIZE };
const emptyTransactionPage: CursorPage<InvestmentTransactionListItem> = { records: [], nextCursor: null, hasMore: false, size: PAGE_SIZE };

function badgeTone(status: string) { return status === 'UNCHANGED' ? 'primary' : status === 'REVERSED' ? 'warning' : status === 'REPLACED' ? 'purple' : 'danger'; }

export default function Investments() {
    const [portfolio, setPortfolio] = useState<InvestmentPortfolio | null>(null);
    const [portfolioError, setPortfolioError] = useState<string | null>(null);
    const [positionStatus, setPositionStatus] = useState<InvestmentPositionStatus>('OPEN');
    const [positionCursor, setPositionCursor] = useState<CursorPageState>(createCursorPageState);
    const [positions, setPositions] = useState(emptyPositionPage);
    const [positionsLoading, setPositionsLoading] = useState(true);
    const [positionsError, setPositionsError] = useState<string | null>(null);
    const [transactionCursor, setTransactionCursor] = useState<CursorPageState>(createCursorPageState);
    const [transactions, setTransactions] = useState(emptyTransactionPage);
    const [transactionsLoading, setTransactionsLoading] = useState(true);
    const [transactionsError, setTransactionsError] = useState<string | null>(null);
    const [transactionType, setTransactionType] = useState<InvestmentTransactionType | ''>('');
    const [correctionStatus, setCorrectionStatus] = useState<InvestmentCorrectionStatus | ''>('');
    const [from, setFrom] = useState('');
    const [to, setTo] = useState('');
    const [transactionPositionId, setTransactionPositionId] = useState<number | null>(null);
    const [positionDetail, setPositionDetail] = useState<InvestmentPositionDetail | null>(null);
    const [positionDetailLoading, setPositionDetailLoading] = useState(false);
    const [transactionDetail, setTransactionDetail] = useState<InvestmentTransactionDetail | null>(null);
    const [transactionDetailLoading, setTransactionDetailLoading] = useState(false);
    const [auditTimeline, setAuditTimeline] = useState<InvestmentAuditTimeline | null>(null);
    const [auditTimelineLoading, setAuditTimelineLoading] = useState(false);
    const [detailError, setDetailError] = useState<string | null>(null);
    const positionDetailRequests = useRef(createLatestRequestGate());
    const transactionDetailRequests = useRef(createLatestRequestGate());
    const auditTimelineRequests = useRef(createLatestRequestGate());

    useEffect(() => {
        let active = true;
        void fetchInvestmentPortfolio().then(data => { if (active) setPortfolio(data); }).catch(error => { if (active) setPortfolioError(getInvestmentReadErrorMessage(error, 'general')); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        let active = true;
        setPositionsLoading(true); setPositionsError(null);
        void fetchInvestmentPositions({ status: positionStatus, cursor: positionCursor.currentCursor, size: PAGE_SIZE })
            .then(data => { if (active) setPositions(data); })
            .catch(error => { if (active) { setPositions(emptyPositionPage); setPositionsError(getInvestmentReadErrorMessage(error, 'filter')); } })
            .finally(() => { if (active) setPositionsLoading(false); });
        return () => { active = false; };
    }, [positionStatus, positionCursor.currentCursor]);

    useEffect(() => {
        let active = true;
        setTransactionsLoading(true); setTransactionsError(null);
        const instant = (value: string) => value ? new Date(value).toISOString() : undefined;
        void fetchInvestmentTransactions({ positionId: transactionPositionId, type: transactionType, correctionStatus, from: instant(from), to: instant(to), cursor: transactionCursor.currentCursor, size: PAGE_SIZE })
            .then(data => { if (active) setTransactions(data); })
            .catch(error => { if (active) { setTransactions(emptyTransactionPage); setTransactionsError(getInvestmentReadErrorMessage(error, 'filter')); } })
            .finally(() => { if (active) setTransactionsLoading(false); });
        return () => { active = false; };
    }, [transactionPositionId, transactionType, correctionStatus, from, to, transactionCursor.currentCursor]);

    function resetTransactionCursor() { setTransactionCursor(resetCursorPageState()); }
    function updatePositionStatus(value: InvestmentPositionStatus) { setPositionStatus(value); setPositionCursor(resetCursorPageState()); }
    function viewPosition(positionId: number) {
        const requestId = positionDetailRequests.current.begin();
        transactionDetailRequests.current.invalidate(); auditTimelineRequests.current.invalidate();
        setPositionDetailLoading(true); setTransactionDetailLoading(false); setAuditTimelineLoading(false); setDetailError(null);
        setPositionDetail(null); setTransactionDetail(null); setAuditTimeline(null);
        void fetchInvestmentPosition(positionId)
            .then(data => { if (positionDetailRequests.current.isCurrent(requestId)) setPositionDetail(data); })
            .catch(error => { if (positionDetailRequests.current.isCurrent(requestId)) setDetailError(getInvestmentReadErrorMessage(error, 'detail')); })
            .finally(() => { if (positionDetailRequests.current.isCurrent(requestId)) setPositionDetailLoading(false); });
    }
    function viewPositionTransactions(positionId: number) { setTransactionPositionId(positionId); resetTransactionCursor(); }
    function viewTransaction(logicalTransactionId: number) {
        const requestId = transactionDetailRequests.current.begin();
        positionDetailRequests.current.invalidate(); auditTimelineRequests.current.invalidate();
        setTransactionDetailLoading(true); setPositionDetailLoading(false); setAuditTimelineLoading(false); setDetailError(null);
        setPositionDetail(null); setTransactionDetail(null); setAuditTimeline(null);
        void fetchInvestmentTransaction(logicalTransactionId)
            .then(data => { if (transactionDetailRequests.current.isCurrent(requestId)) setTransactionDetail(data); })
            .catch(error => { if (transactionDetailRequests.current.isCurrent(requestId)) setDetailError(getInvestmentReadErrorMessage(error, 'detail')); })
            .finally(() => { if (transactionDetailRequests.current.isCurrent(requestId)) setTransactionDetailLoading(false); });
    }
    function loadAudit() {
        if (!transactionDetail) return;
        const requestId = auditTimelineRequests.current.begin();
        setDetailError(null); setAuditTimeline(null); setAuditTimelineLoading(true);
        void fetchInvestmentAuditTimeline(transactionDetail.logicalTransactionId)
            .then(data => { if (auditTimelineRequests.current.isCurrent(requestId)) setAuditTimeline(data); })
            .catch(error => { if (auditTimelineRequests.current.isCurrent(requestId)) setDetailError(getInvestmentReadErrorMessage(error, 'detail')); })
            .finally(() => { if (auditTimelineRequests.current.isCurrent(requestId)) setAuditTimelineLoading(false); });
    }

    return <div className="investment-workspace">
        <PageHeader title="投资账本" subtitle="仅统计投资账本中的 transaction-driven Position；不包含账户现金、Legacy 资产或全部净资产。" />
        {portfolioError && <AlertMessage type="error">{portfolioError}</AlertMessage>}
        <section aria-labelledby="portfolio-heading">
            <div className="section-heading"><div><p className="section-heading__eyebrow">PORTFOLIO</p><h2 id="portfolio-heading">投资账本持仓</h2></div><span className="table-secondary">后端聚合的只读账本视图</span></div>
            {!portfolio && !portfolioError ? <LoadingState /> : portfolio && <PortfolioSummary portfolio={portfolio} />}
        </section>

        <section className="content-card investment-section" aria-labelledby="positions-heading">
            <div className="section-heading"><div><p className="section-heading__eyebrow">POSITIONS</p><h2 id="positions-heading">持仓</h2></div>
                <label className="field investment-status-filter"><span className="field__label">持仓状态</span><select className="field__control" value={positionStatus} onChange={event => updatePositionStatus(event.target.value as InvestmentPositionStatus)}><option value="OPEN">持仓中</option><option value="CLOSED">已关闭</option><option value="ALL">全部</option></select></label>
            </div>
            {positionsError && <AlertMessage type="error">{positionsError}</AlertMessage>}
            {positionsLoading ? <LoadingState /> : <><div className="table-scroll"><table className="data-table investment-table"><thead><tr><th scope="col">标的 / 账户</th><th scope="col">市场</th><th scope="col">数量</th><th scope="col">平均成本</th><th scope="col">总成本</th><th scope="col">累计已实现盈亏</th><th scope="col">市场参考估值</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>
                {positions.records.map(position => <tr key={position.positionId}><td><strong className="table-primary">{position.instrument.name}</strong><span className="table-secondary">{position.instrument.symbol} · {position.account.displayName}</span></td><td>{position.instrument.market}<span className="table-secondary">{position.instrument.assetClass} · {position.instrument.quoteCurrency}</span></td><td className="amount">{formatInvestmentQuantity(position.quantity)}</td><td className="amount">{formatInvestmentMoney(position.averageCost, position.instrument.quoteCurrency)}</td><td className="amount">{formatInvestmentMoney(position.totalCost, position.instrument.quoteCurrency)}</td><td className="amount">{formatInvestmentMoney(position.cumulativeRealizedProfitLoss, portfolio?.currency)}</td><td><ReferenceValue value={position.referenceValuation.value} currency={position.referenceValuation.baseCurrency} freshness={position.referenceValuation.freshness} /></td><td><Badge tone={position.status === 'OPEN' ? 'success' : 'primary'}>{position.status === 'OPEN' ? '持仓中' : '已关闭'}</Badge></td><td className="cell-actions"><div className="data-table__actions"><button type="button" className="button button--secondary button--small" onClick={() => viewPosition(position.positionId)}>详情</button><button type="button" className="button button--secondary button--small" onClick={() => viewPositionTransactions(position.positionId)}>查看该持仓交易</button></div></td></tr>)}
                {positions.records.length === 0 && <EmptyTableRow colSpan={9} message="暂无符合条件的投资账本持仓。" />}
            </tbody></table></div><CursorPager cursor={positionCursor} page={positions} onPrevious={() => setPositionCursor(goToPreviousCursorPage)} onNext={() => positions.nextCursor && setPositionCursor(value => goToNextCursorPage(value, positions.nextCursor!))} /></>}
        </section>

        <section className="content-card investment-section" aria-labelledby="transactions-heading">
            <div className="section-heading"><div><p className="section-heading__eyebrow">LOGICAL TRANSACTIONS</p><h2 id="transactions-heading">投资交易</h2></div>{transactionPositionId && <button type="button" className="button button--secondary button--small" onClick={() => { setTransactionPositionId(null); resetTransactionCursor(); }}>清除持仓筛选</button>}</div>
            <div className="filter-grid investment-filter-grid"><Field label="类型"><select className="field__control" value={transactionType} onChange={event => { setTransactionType(event.target.value as InvestmentTransactionType | ''); resetTransactionCursor(); }}><option value="">全部类型</option><option value="OPENING_POSITION">期初持仓</option><option value="BUY">买入</option><option value="SELL">卖出</option><option value="DIVIDEND">分红</option></select></Field><Field label="纠正状态"><select className="field__control" value={correctionStatus} onChange={event => { setCorrectionStatus(event.target.value as InvestmentCorrectionStatus | ''); resetTransactionCursor(); }}><option value="">全部状态</option><option value="UNCHANGED">原始</option><option value="REVERSED">已冲正</option><option value="REPLACED">已替换</option></select></Field><Field label="起始时间"><input className="field__control" type="datetime-local" value={from} onChange={event => { setFrom(event.target.value); resetTransactionCursor(); }} /></Field><Field label="结束时间"><input className="field__control" type="datetime-local" value={to} onChange={event => { setTo(event.target.value); resetTransactionCursor(); }} /></Field></div>
            {transactionsError && <AlertMessage type="error">{transactionsError}</AlertMessage>}
            {transactionsLoading ? <LoadingState /> : <><div className="table-scroll"><table className="data-table investment-table"><thead><tr><th scope="col">生效交易时间</th><th scope="col">类型</th><th scope="col">标的 / 账户</th><th scope="col">数量</th><th scope="col">单价</th><th scope="col">净额</th><th scope="col">已实现盈亏</th><th scope="col">纠正状态</th><th scope="col">操作</th></tr></thead><tbody>
                {transactions.records.map(transaction => <tr key={transaction.logicalTransactionId}><td>{formatInvestmentDateTime(transaction.effectiveTradeTime)}<span className="table-secondary">{transaction.effective ? '当前有效' : '已不再有效'}</span></td><td>{investmentTransactionTypeLabel(transaction.transactionType)}</td><td><strong className="table-primary">{transaction.instrument.name}</strong><span className="table-secondary">{transaction.instrument.symbol} · {transaction.account.displayName}</span></td><td className="amount">{formatInvestmentQuantity(transaction.quantity)}</td><td className="amount">{formatInvestmentMoney(transaction.unitPrice, transaction.instrument.quoteCurrency)}</td><td className="amount">{formatInvestmentMoney(transaction.netAmount, portfolio?.currency)}</td><td className="amount">{formatInvestmentMoney(transaction.realizedProfitLoss, portfolio?.currency)}</td><td><Badge tone={badgeTone(transaction.correctionStatus)}>{investmentCorrectionStatusLabel(transaction.correctionStatus)}</Badge>{transaction.correctionCreatedAt && <span className="table-secondary">纠正时间：{formatInvestmentDateTime(transaction.correctionCreatedAt)}</span>}</td><td><button type="button" className="button button--secondary button--small" onClick={() => viewTransaction(transaction.logicalTransactionId)}>详情</button></td></tr>)}
                {transactions.records.length === 0 && <EmptyTableRow colSpan={9} message="暂无符合条件的逻辑交易。" />}
            </tbody></table></div><CursorPager cursor={transactionCursor} page={transactions} onPrevious={() => setTransactionCursor(goToPreviousCursorPage)} onNext={() => transactions.nextCursor && setTransactionCursor(value => goToNextCursorPage(value, transactions.nextCursor!))} /></>}
        </section>

        <section className="investment-details" aria-live="polite">
            {(positionDetailLoading || transactionDetailLoading) && <LoadingState />}
            {detailError && <AlertMessage type="error">{detailError}</AlertMessage>}
            {positionDetail && <PositionDetail detail={positionDetail} />}
            {transactionDetail && <TransactionDetail detail={transactionDetail} auditTimeline={auditTimeline} auditTimelineLoading={auditTimelineLoading} onLoadAudit={loadAudit} />}
        </section>
    </div>;
}

function PortfolioSummary({ portfolio }: { portfolio: InvestmentPortfolio }) {
    const reference = portfolio.referenceValuation;
    return <><div className="summary-grid investment-summary-grid"><SummaryCard icon="investments" label="持仓总数" tone="primary">{portfolio.positionCount}</SummaryCard><SummaryCard icon="assets" label="持仓中" tone="success">{portfolio.openPositionCount}</SummaryCard><SummaryCard icon="check" label="已关闭" tone="warning">{portfolio.closedPositionCount}</SummaryCard><SummaryCard icon="wallet" label="持仓中总成本" tone="primary">{formatInvestmentMoney(portfolio.openTotalCost, portfolio.currency)}</SummaryCard><SummaryCard icon="balance" label="累计已实现盈亏" tone="purple">{formatInvestmentMoney(portfolio.cumulativeRealizedProfitLoss, portfolio.currency)}</SummaryCard><SummaryCard icon="activity" label="市场参考估值" tone="warning"><ReferenceValue value={reference.value} currency={reference.baseCurrency} freshness={reference.freshness} /></SummaryCard></div><div className="investment-reference-note"><strong>市场参考估值</strong><span>非账务数据；覆盖 {reference.valuedPositionCount} / {reference.totalOpenPositionCount} 个持仓，状态：{investmentFreshnessLabel(reference.freshness)}。</span>{investmentWarningMessages(reference.warnings).length > 0 && <ul>{investmentWarningMessages(reference.warnings).map(warning => <li key={warning}>{warning}</li>)}</ul>}</div></>;
}

function ReferenceValue({ value, currency, freshness }: { value: string | null; currency: string; freshness: string }) { return <div><span className="amount">{value === null ? '暂无可用市场参考估值' : formatInvestmentMoney(value, currency)}</span><span className="table-secondary">{investmentFreshnessLabel(freshness)} · 非账务数据</span></div>; }
function CursorPager<T>({ cursor, page, onPrevious, onNext }: { cursor: CursorPageState; page: CursorPage<T>; onPrevious: () => void; onNext: () => void; }) { return <nav className="pagination" aria-label="游标分页"><span className="pagination__summary">当前页 {page.records.length} 条记录</span><div className="pagination__actions"><button type="button" className="pagination__button" disabled={cursor.cursorHistory.length === 0} onClick={onPrevious}>上一页</button><button type="button" className="pagination__button" disabled={!page.hasMore || !page.nextCursor} onClick={onNext}>下一页</button></div></nav>; }
function Field({ label, children }: { label: string; children: React.ReactNode }) { return <label className="field"><span className="field__label">{label}</span>{children}</label>; }
function LoadingState() { return <div className="investment-loading" role="status">正在加载投资账本数据…</div>; }

function PositionDetail({ detail }: { detail: InvestmentPositionDetail }) {
    const reference = detail.referenceValuation;
    return <article className="content-card investment-detail"><div className="section-heading"><div><p className="section-heading__eyebrow">POSITION DETAIL</p><h2>持仓详情</h2></div><Badge tone={detail.status === 'OPEN' ? 'success' : 'primary'}>{detail.status === 'OPEN' ? '持仓中' : '已关闭'}</Badge></div><DetailGrid items={[['账户', detail.account.displayName], ['标的', `${detail.instrument.name} (${detail.instrument.symbol})`], ['市场 / 资产类别', `${detail.instrument.market} · ${detail.instrument.assetClass}`], ['报价货币', detail.instrument.quoteCurrency], ['当前持仓数量', formatInvestmentQuantity(detail.quantity)], ['平均成本', formatInvestmentMoney(detail.averageCost, detail.instrument.quoteCurrency)], ['总成本', formatInvestmentMoney(detail.totalCost, detail.instrument.quoteCurrency)], ['累计已实现盈亏', formatInvestmentMoney(detail.cumulativeRealizedProfitLoss)]]} />
        {detail.manualReference && <DetailSection title="人工参考数据"><DetailGrid items={[[`人工参考价格 (${detail.instrument.quoteCurrency})`, formatInvestmentMoney(detail.manualReference.currentPrice, detail.instrument.quoteCurrency)], [`人工参考市值 (${detail.instrument.quoteCurrency})`, formatInvestmentMoney(detail.manualReference.marketValue, detail.instrument.quoteCurrency)]]} /></DetailSection>}
        <DetailSection title="缓存市场参考估值"><p className="investment-detail__note">非账务数据，不构成当前持仓真值。</p><DetailGrid items={[[`行情价格 (${reference.quoteCurrency ?? detail.instrument.quoteCurrency})`, formatInvestmentMoney(reference.quotePrice, reference.quoteCurrency ?? detail.instrument.quoteCurrency)], ['行情时间', formatInvestmentDateTime(reference.quoteTime)], ['行情来源', reference.quoteProvider ?? '暂无'], ['汇率', reference.fxRate ?? '暂无'], ['市场参考估值', formatInvestmentMoney(reference.baseCurrencyValue, reference.baseCurrency)], ['估值新鲜度', investmentFreshnessLabel(reference.freshness)]]} />{investmentWarningMessages(reference.warnings).length > 0 && <ul className="investment-warnings">{investmentWarningMessages(reference.warnings).map(warning => <li key={warning}>{warning}</li>)}</ul>}</DetailSection>
    </article>;
}

function TransactionDetail({ detail, auditTimeline, auditTimelineLoading, onLoadAudit }: { detail: InvestmentTransactionDetail; auditTimeline: InvestmentAuditTimeline | null; auditTimelineLoading: boolean; onLoadAudit: () => void; }) {
    const correctionCopy = detail.correctionStatus === 'REVERSED' ? '此交易已冲正。原始事实保留用于审计，冲正通过追加不可变事实完成。' : detail.correctionStatus === 'REPLACED' ? '此交易已被替换。原始记录与替换后的有效记录分别保留。' : null;
    return <article className="content-card investment-detail"><div className="section-heading"><div><p className="section-heading__eyebrow">LOGICAL TRANSACTION DETAIL</p><h2>投资交易详情</h2></div><Badge tone={badgeTone(detail.correctionStatus)}>{investmentCorrectionStatusLabel(detail.correctionStatus)}</Badge></div>{correctionCopy && <AlertMessage type="warning">{correctionCopy}</AlertMessage>}<DetailGrid items={[["交易类型", investmentTransactionTypeLabel(detail.transactionType)], ['生效交易时间', formatInvestmentDateTime(detail.effectiveTradeTime)], ['结算时间', formatInvestmentDateTime(detail.settlementTime)], ['账户', detail.account.displayName], ['标的', `${detail.instrument.name} (${detail.instrument.symbol})`], ['当前有效', detail.effective ? '是' : '否']]} />
        <DetailSection title="原始记录"><BusinessValues values={detail.originalBusinessValues} currency={detail.instrument.quoteCurrency} /></DetailSection>
        {detail.effectiveBusinessValues && <DetailSection title="替换后的有效记录"><BusinessValues values={detail.effectiveBusinessValues} currency={detail.instrument.quoteCurrency} /></DetailSection>}
        {detail.correction && <DetailSection title="纠正信息"><DetailGrid items={[["纠正原因", detail.correction.reason], ['纠正时间', formatInvestmentDateTime(detail.correction.createdAt)]]} /></DetailSection>}
        <div className="investment-receipt-grid"><ReceiptCard title="原始入账回执" receipt={detail.postingReceipt} />{detail.correctionFinalReceipt && <ReceiptCard title="纠正完成回执" receipt={detail.correctionFinalReceipt} />}<CurrentPositionCard position={detail.currentPosition} /></div>
        <DetailSection title="不可变审计时间线"><button type="button" className="button button--secondary" onClick={onLoadAudit} disabled={auditTimelineLoading}>{auditTimelineLoading ? '正在加载审计时间线…' : '查看审计时间线'}</button>{auditTimeline && <AuditTimeline timeline={auditTimeline} currency={detail.instrument.quoteCurrency} />}</DetailSection>
    </article>;
}

function DetailSection({ title, children }: { title: string; children: React.ReactNode }) { return <section className="investment-detail-section"><h3>{title}</h3>{children}</section>; }
function DetailGrid({ items }: { items: Array<[string, string]> }) { return <div className="detail-grid">{items.map(([label, value]) => <div className="detail-item" key={label}><div className="detail-item__label">{label}</div><div className="detail-item__value">{value}</div></div>)}</div>; }
function BusinessValues({ values, currency }: { values: InvestmentBusinessValues; currency: string }) { return <DetailGrid items={[["数量", formatInvestmentQuantity(values.quantity)], ['单价', formatInvestmentMoney(values.unitPrice, currency)], ['总额', formatInvestmentMoney(values.grossAmount, currency)], ['费用', formatInvestmentMoney(values.feeAmount, currency)], ['税费', formatInvestmentMoney(values.taxAmount, currency)], ['净额', formatInvestmentMoney(values.netAmount, currency)], ['已释放成本', formatInvestmentMoney(values.releasedCostAmount, currency)], ['已实现盈亏', formatInvestmentMoney(values.realizedProfitLoss, currency)], ['备注', values.note || '暂无'], ['外部参考号', values.externalReference || '暂无']]} />; }
function ReceiptCard({ title, receipt }: { title: string; receipt: InvestmentReceipt | null }) { return <section className="investment-receipt"><h3>{title}</h3>{receipt ? <DetailGrid items={[["账户余额", formatInvestmentMoney(receipt.accountBalanceAfter)], ['持仓数量', formatInvestmentQuantity(receipt.positionQuantityAfter)], ['持仓平均成本', formatInvestmentMoney(receipt.positionAverageCostAfter)], ['持仓总成本', formatInvestmentMoney(receipt.positionTotalCostAfter)], ['持仓已实现盈亏', formatInvestmentMoney(receipt.positionRealizedProfitLossAfter)], ['持仓状态', receipt.positionStatusAfter ?? '暂无']]} /> : <EmptyState compact message="入账时快照不适用。" />}</section>; }
function CurrentPositionCard({ position }: { position: InvestmentCurrentPosition }) { return <section className="investment-receipt"><h3>当前持仓</h3><DetailGrid items={[["数量", formatInvestmentQuantity(position.quantity)], ['平均成本', formatInvestmentMoney(position.averageCost)], ['总成本', formatInvestmentMoney(position.totalCost)], ['累计已实现盈亏', formatInvestmentMoney(position.cumulativeRealizedProfitLoss)], ['状态', position.status ?? '暂无']]} /></section>; }
function AuditTimeline({ timeline, currency }: { timeline: InvestmentAuditTimeline; currency: string }) { return <ol className="audit-timeline">{timeline.events.map((event, index) => <li key={`${event.eventKind}-${index}`}><div className="audit-timeline__marker" /><div className="audit-timeline__content"><strong>{investmentAuditEventLabel(event.eventKind)}</strong><span>{formatInvestmentDateTime(event.createdAt)} · {investmentReceiptApplicabilityLabel(event.receiptApplicability)}</span>{event.reason && <p>纠正原因：{event.reason}</p>}{event.businessValues && <BusinessValues values={event.businessValues} currency={currency} />}{event.facts.length > 0 && <div className="audit-timeline__facts">{event.facts.map(fact => <div key={`${fact.role}-${fact.physicalFactId}`}><strong>{investmentAuditFactRoleLabel(fact.role)}</strong><span>{investmentReceiptApplicabilityLabel(fact.receiptApplicability)}</span><BusinessValues values={fact.businessValues} currency={currency} /></div>)}</div>}</div></li>)}</ol>; }
