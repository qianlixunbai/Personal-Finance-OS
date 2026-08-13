import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
    fetchInvestmentAuditTimeline, fetchInvestmentPortfolio, fetchInvestmentPosition,
    fetchInvestmentPositions, fetchInvestmentTransaction, fetchInvestmentTransactions,
} from '../api/investment';
import { AlertMessage, EmptyState, EmptyTableRow } from '../components/Feedback';
import { PageHeader } from '../components/PageHeader';
import { Badge, SummaryCard } from '../components/Visual';
import { InvestmentCommandDialog } from '../components/investment/InvestmentCommandDialog';
import { PendingCommandRecoveryBanner } from '../components/investment/PendingCommandRecoveryBanner';
import { useInvestmentCommandCoordinator } from '../hooks/useInvestmentCommandCoordinator';
import type { InvestmentCommandDraft, InvestmentCommandType, PendingInvestmentCommandV1 } from '../types/investmentCommand';
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
import { canOfferBuyWithAuthoritativeEligibility } from '../utils/investmentCommandValidation';

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
    const [transactionDetailPositionId, setTransactionDetailPositionId] = useState<number | null>(null);
    const [transactionDetailLoading, setTransactionDetailLoading] = useState(false);
    const [auditTimeline, setAuditTimeline] = useState<InvestmentAuditTimeline | null>(null);
    const [auditTimelineLoading, setAuditTimelineLoading] = useState(false);
    const [detailError, setDetailError] = useState<string | null>(null);
    const [commandType, setCommandType] = useState<InvestmentCommandType | null>(null);
    const commandOriginRef = useRef<HTMLElement | null>(null);
    const portfolioRequests = useRef(createLatestRequestGate());
    const positionListRequests = useRef(createLatestRequestGate());
    const transactionListRequests = useRef(createLatestRequestGate());
    const positionDetailRequests = useRef(createLatestRequestGate());
    const transactionDetailRequests = useRef(createLatestRequestGate());
    const auditTimelineRequests = useRef(createLatestRequestGate());
    const refreshAfterCommand = async (record: PendingInvestmentCommandV1) => {
        portfolioRequests.current.invalidate(); positionListRequests.current.invalidate(); transactionListRequests.current.invalidate();
        positionDetailRequests.current.invalidate(); transactionDetailRequests.current.invalidate(); auditTimelineRequests.current.invalidate();
        const portfolioRequest = portfolioRequests.current.begin(); const positionListRequest = positionListRequests.current.begin(); const transactionListRequest = transactionListRequests.current.begin();
        const positionDetailRequest = positionDetailRequests.current.begin();
        const transactionDetailRequest = record.commandType === 'REVERSAL' || record.commandType === 'REPLACEMENT' ? transactionDetailRequests.current.begin() : null;
        const auditRequest = record.commandType === 'REVERSAL' || record.commandType === 'REPLACEMENT' ? auditTimelineRequests.current.begin() : null;
        const positionQuery = { status: positionStatus, cursor: null, size: PAGE_SIZE };
        const transactionQuery = { positionId: transactionPositionId, type: transactionType, correctionStatus, from: from ? new Date(from).toISOString() : undefined, to: to ? new Date(to).toISOString() : undefined, cursor: null, size: PAGE_SIZE };
        const reads: Array<Promise<unknown>> = [fetchInvestmentPortfolio(), fetchInvestmentPositions(positionQuery), fetchInvestmentPosition(record.refresh!.assetId), fetchInvestmentTransactions(transactionQuery)];
        if (record.commandType === 'REVERSAL' || record.commandType === 'REPLACEMENT') reads.push(fetchInvestmentTransaction(record.refresh!.logicalTransactionId), fetchInvestmentAuditTimeline(record.refresh!.logicalTransactionId));
        const [nextPortfolio, nextPositions, nextPosition, nextTransactions, nextTransaction, nextAudit] = await Promise.all(reads) as [InvestmentPortfolio, CursorPage<InvestmentPositionListItem>, InvestmentPositionDetail, CursorPage<InvestmentTransactionListItem>, InvestmentTransactionDetail?, InvestmentAuditTimeline?];
        if (portfolioRequests.current.isCurrent(portfolioRequest)) setPortfolio(nextPortfolio);
        if (positionListRequests.current.isCurrent(positionListRequest)) { setPositions(nextPositions); setPositionCursor(resetCursorPageState()); }
        if (positionDetailRequests.current.isCurrent(positionDetailRequest)) setPositionDetail(nextPosition);
        if (transactionListRequests.current.isCurrent(transactionListRequest)) { setTransactions(nextTransactions); setTransactionCursor(resetCursorPageState()); }
        if (nextTransaction && transactionDetailRequest !== null && transactionDetailRequests.current.isCurrent(transactionDetailRequest)) setTransactionDetail(nextTransaction);
        if (nextAudit && auditRequest !== null && auditTimelineRequests.current.isCurrent(auditRequest)) setAuditTimeline(nextAudit);
    };
    const reconcileAfterConflict = async (record: PendingInvestmentCommandV1) => {
        portfolioRequests.current.invalidate(); positionListRequests.current.invalidate(); transactionListRequests.current.invalidate();
        positionDetailRequests.current.invalidate(); transactionDetailRequests.current.invalidate(); auditTimelineRequests.current.invalidate();
        const portfolioRequest = portfolioRequests.current.begin(); const positionListRequest = positionListRequests.current.begin(); const transactionListRequest = transactionListRequests.current.begin();
        const positionDetailRequest = record.commandType !== 'FIRST_BUY' ? positionDetailRequests.current.begin() : null;
        const transactionDetailRequest = record.commandType === 'REVERSAL' || record.commandType === 'REPLACEMENT' ? transactionDetailRequests.current.begin() : null;
        const auditRequest = record.commandType === 'REVERSAL' || record.commandType === 'REPLACEMENT' ? auditTimelineRequests.current.begin() : null;
        const positionQuery = record.commandType === 'FIRST_BUY'
            ? { status: 'ALL' as const, accountId: record.target.accountId, instrumentId: record.target.instrumentId, cursor: null, size: PAGE_SIZE }
            : { status: positionStatus, cursor: null, size: PAGE_SIZE };
        const transactionQuery = record.commandType === 'FIRST_BUY'
            ? { accountId: record.target.accountId, instrumentId: record.target.instrumentId, cursor: null, size: PAGE_SIZE }
            : { positionId: transactionPositionId, type: transactionType, correctionStatus, from: from ? new Date(from).toISOString() : undefined, to: to ? new Date(to).toISOString() : undefined, cursor: null, size: PAGE_SIZE };
        const reads: Array<Promise<unknown>> = [fetchInvestmentPortfolio(), fetchInvestmentPositions(positionQuery), fetchInvestmentTransactions(transactionQuery)];
        if (record.commandType !== 'FIRST_BUY') reads.splice(2, 0, fetchInvestmentPosition(record.target.assetId!));
        if (record.commandType === 'REVERSAL' || record.commandType === 'REPLACEMENT') reads.push(fetchInvestmentTransaction(record.target.logicalTransactionId!), fetchInvestmentAuditTimeline(record.target.logicalTransactionId!));
        const results = await Promise.all(reads);
        const [nextPortfolio, nextPositions, nextPositionOrTransactions, nextTransactionsOrTransaction, maybeTransaction, maybeAudit] = results as [InvestmentPortfolio, CursorPage<InvestmentPositionListItem>, InvestmentPositionDetail | CursorPage<InvestmentTransactionListItem>, (CursorPage<InvestmentTransactionListItem> | InvestmentTransactionDetail | undefined)?, InvestmentTransactionDetail?, InvestmentAuditTimeline?];
        const firstBuy = record.commandType === 'FIRST_BUY';
        const nextTransactions = (firstBuy ? nextPositionOrTransactions : nextTransactionsOrTransaction) as CursorPage<InvestmentTransactionListItem>;
        if (portfolioRequests.current.isCurrent(portfolioRequest)) setPortfolio(nextPortfolio);
        if (positionListRequests.current.isCurrent(positionListRequest)) { setPositions(nextPositions); setPositionCursor(resetCursorPageState()); }
        if (transactionListRequests.current.isCurrent(transactionListRequest)) { setTransactions(nextTransactions); setTransactionCursor(resetCursorPageState()); }
        if (!firstBuy && positionDetailRequest !== null && positionDetailRequests.current.isCurrent(positionDetailRequest)) setPositionDetail(nextPositionOrTransactions as InvestmentPositionDetail);
        if (record.commandType === 'REVERSAL' || record.commandType === 'REPLACEMENT') {
            if (transactionDetailRequest !== null && transactionDetailRequests.current.isCurrent(transactionDetailRequest)) setTransactionDetail(maybeTransaction!);
            if (auditRequest !== null && auditTimelineRequests.current.isCurrent(auditRequest)) setAuditTimeline(maybeAudit!);
        }
    };
    const coordinator = useInvestmentCommandCoordinator({ refresh: refreshAfterCommand, reconcile: reconcileAfterConflict });
    const openCommand = (type: InvestmentCommandType) => {
        if (coordinator.writeDisabled) return;
        commandOriginRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        setCommandType(type);
    };
    const submitCommand = async (draft: InvestmentCommandDraft) => { await coordinator.submit(draft); };

    useLayoutEffect(() => {
        if (!commandType && commandOriginRef.current) {
            const origin = commandOriginRef.current;
            commandOriginRef.current = null;
            origin.focus();
        }
    }, [commandType]);

    useEffect(() => {
        let active = true;
        const requestId = portfolioRequests.current.begin();
        void fetchInvestmentPortfolio().then(data => { if (active && portfolioRequests.current.isCurrent(requestId)) setPortfolio(data); }).catch(error => { if (active && portfolioRequests.current.isCurrent(requestId)) setPortfolioError(getInvestmentReadErrorMessage(error, 'general')); });
        return () => { active = false; };
    }, []);

    useEffect(() => {
        let active = true;
        const requestId = positionListRequests.current.begin();
        setPositionsLoading(true); setPositionsError(null);
        void fetchInvestmentPositions({ status: positionStatus, cursor: positionCursor.currentCursor, size: PAGE_SIZE })
            .then(data => { if (active && positionListRequests.current.isCurrent(requestId)) setPositions(data); })
            .catch(error => { if (active && positionListRequests.current.isCurrent(requestId)) { setPositions(emptyPositionPage); setPositionsError(getInvestmentReadErrorMessage(error, 'filter')); } })
            .finally(() => { if (active && positionListRequests.current.isCurrent(requestId)) setPositionsLoading(false); });
        return () => { active = false; };
    }, [positionStatus, positionCursor.currentCursor]);

    useEffect(() => {
        let active = true;
        const requestId = transactionListRequests.current.begin();
        setTransactionsLoading(true); setTransactionsError(null);
        const instant = (value: string) => value ? new Date(value).toISOString() : undefined;
        void fetchInvestmentTransactions({ positionId: transactionPositionId, type: transactionType, correctionStatus, from: instant(from), to: instant(to), cursor: transactionCursor.currentCursor, size: PAGE_SIZE })
            .then(data => { if (active && transactionListRequests.current.isCurrent(requestId)) setTransactions(data); })
            .catch(error => { if (active && transactionListRequests.current.isCurrent(requestId)) { setTransactions(emptyTransactionPage); setTransactionsError(getInvestmentReadErrorMessage(error, 'filter')); } })
            .finally(() => { if (active && transactionListRequests.current.isCurrent(requestId)) setTransactionsLoading(false); });
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
    function viewTransaction(logicalTransactionId: number, positionId?: number) {
        const requestId = transactionDetailRequests.current.begin();
        positionDetailRequests.current.invalidate(); auditTimelineRequests.current.invalidate();
        setTransactionDetailLoading(true); setPositionDetailLoading(false); setAuditTimelineLoading(false); setDetailError(null);
        setPositionDetail(null); setTransactionDetail(null); setAuditTimeline(null); setTransactionDetailPositionId(positionId ?? null);
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
        <PageHeader title="投资账本" subtitle="仅统计投资账本中的 transaction-driven Position；不包含账户现金、Legacy 资产或全部净资产。" actions={<button type="button" className="button button--primary" disabled={coordinator.writeDisabled} onClick={() => openCommand('FIRST_BUY')}>记录第一笔投资</button>} />
        <PendingCommandRecoveryBanner pending={coordinator.pending} onRecover={() => void coordinator.recover().catch(error => setDetailError(error instanceof Error ? error.message : '恢复失败'))} onExport={coordinator.exportEvidence} />
        {coordinator.receipt && <CommandReceipt receipt={coordinator.receipt} refreshed={coordinator.receiptRefreshed} currency={portfolio?.currency ?? 'CNY'} />}
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
                {transactions.records.map(transaction => <tr key={transaction.logicalTransactionId}><td>{formatInvestmentDateTime(transaction.effectiveTradeTime)}<span className="table-secondary">{transaction.effective ? '当前有效' : '已不再有效'}</span></td><td>{investmentTransactionTypeLabel(transaction.transactionType)}</td><td><strong className="table-primary">{transaction.instrument.name}</strong><span className="table-secondary">{transaction.instrument.symbol} · {transaction.account.displayName}</span></td><td className="amount">{formatInvestmentQuantity(transaction.quantity)}</td><td className="amount">{formatInvestmentMoney(transaction.unitPrice, transaction.instrument.quoteCurrency)}</td><td className="amount">{formatInvestmentMoney(transaction.netAmount, portfolio?.currency)}</td><td className="amount">{formatInvestmentMoney(transaction.realizedProfitLoss, portfolio?.currency)}</td><td><Badge tone={badgeTone(transaction.correctionStatus)}>{investmentCorrectionStatusLabel(transaction.correctionStatus)}</Badge>{transaction.correctionCreatedAt && <span className="table-secondary">纠正时间：{formatInvestmentDateTime(transaction.correctionCreatedAt)}</span>}</td><td><button type="button" className="button button--secondary button--small" onClick={() => viewTransaction(transaction.logicalTransactionId, transaction.positionId)}>详情</button></td></tr>)}
                {transactions.records.length === 0 && <EmptyTableRow colSpan={9} message="暂无符合条件的逻辑交易。" />}
            </tbody></table></div><CursorPager cursor={transactionCursor} page={transactions} onPrevious={() => setTransactionCursor(goToPreviousCursorPage)} onNext={() => transactions.nextCursor && setTransactionCursor(value => goToNextCursorPage(value, transactions.nextCursor!))} /></>}
        </section>

        <section className="investment-details" aria-live="polite">
            {(positionDetailLoading || transactionDetailLoading) && <LoadingState />}
            {detailError && <AlertMessage type="error">{detailError}</AlertMessage>}
            {positionDetail && <PositionDetail detail={positionDetail} writeDisabled={coordinator.writeDisabled} onCommand={openCommand} />}
            {transactionDetail && <TransactionDetail detail={transactionDetail} auditTimeline={auditTimeline} auditTimelineLoading={auditTimelineLoading} onLoadAudit={loadAudit} writeDisabled={coordinator.writeDisabled} onCommand={openCommand} />}
        </section>
        {commandType && <InvestmentCommandDialog commandType={commandType} position={positionDetail ?? undefined} transaction={transactionDetail ?? undefined} transactionPositionId={transactionDetailPositionId ?? undefined} onClose={() => setCommandType(null)} onSubmit={submitCommand} />}
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

function CommandReceipt({ receipt, refreshed, currency }: { receipt: import('../types/investmentCommand').InvestmentCommandReceipt; refreshed: boolean; currency: string; }) {
    return <AlertMessage type="success"><div role="status"><p>{receipt.idempotentReplay ? '已恢复此前提交结果，系统没有重复执行该操作。' : '投资记录已提交。'} {refreshed ? '当前数据已从后端刷新。' : '当前账本数据尚未完成刷新，请继续刷新。'}</p><dl className="detail-grid"><div><dt>交易类型</dt><dd>{receipt.transactionType ?? '不适用'}</dd></div><div><dt>现金影响</dt><dd>{formatInvestmentMoney(receipt.cashDelta ?? null, currency)}</dd></div><div><dt>账户余额（提交时）</dt><dd>{formatInvestmentMoney(receipt.balanceAfter ?? null, currency)}</dd></div><div><dt>提交时间</dt><dd>{formatInvestmentDateTime(receipt.createdAt ?? null)}</dd></div></dl></div></AlertMessage>;
}

function PositionDetail({ detail, writeDisabled, onCommand }: { detail: InvestmentPositionDetail; writeDisabled: boolean; onCommand: (type: InvestmentCommandType) => void }) {
    const reference = detail.referenceValuation;
    const eligible = canOfferBuyWithAuthoritativeEligibility(detail.account.status, detail.account.type, detail.instrument.status, detail.instrument.assetClass);
    return <article className="content-card investment-detail"><div className="section-heading"><div><p className="section-heading__eyebrow">POSITION DETAIL</p><h2>持仓详情</h2></div><Badge tone={detail.status === 'OPEN' ? 'success' : 'primary'}>{detail.status === 'OPEN' ? '持仓中' : '已关闭'}</Badge></div><div className="data-table__actions"><button type="button" className="button button--secondary button--small" disabled={writeDisabled || !eligible} onClick={() => onCommand('BUY')}>{detail.status === 'CLOSED' ? '记录买入并重新打开' : '记录买入'}</button><button type="button" className="button button--secondary button--small" disabled={writeDisabled || !eligible || detail.status === 'CLOSED'} onClick={() => onCommand('SELL')}>记录卖出</button><button type="button" className="button button--secondary button--small" disabled={writeDisabled || !eligible} onClick={() => onCommand('DIVIDEND')}>记录分红</button></div>{!eligible && <p className="investment-detail__note" role="alert">账户或标的当前不可用于投资命令。</p>}{detail.status === 'CLOSED' && <p className="investment-detail__note">当前持仓为零，无法记录卖出。</p>}<DetailGrid items={[['账户', detail.account.displayName], ['标的', `${detail.instrument.name} (${detail.instrument.symbol})`], ['市场 / 资产类别', `${detail.instrument.market} · ${detail.instrument.assetClass}`], ['报价货币', detail.instrument.quoteCurrency], ['当前持仓数量', formatInvestmentQuantity(detail.quantity)], ['平均成本', formatInvestmentMoney(detail.averageCost, detail.instrument.quoteCurrency)], ['总成本', formatInvestmentMoney(detail.totalCost, detail.instrument.quoteCurrency)], ['累计已实现盈亏', formatInvestmentMoney(detail.cumulativeRealizedProfitLoss)]]} />
        {detail.manualReference && <DetailSection title="人工参考数据"><DetailGrid items={[[`人工参考价格 (${detail.instrument.quoteCurrency})`, formatInvestmentMoney(detail.manualReference.currentPrice, detail.instrument.quoteCurrency)], [`人工参考市值 (${detail.instrument.quoteCurrency})`, formatInvestmentMoney(detail.manualReference.marketValue, detail.instrument.quoteCurrency)]]} /></DetailSection>}
        <DetailSection title="缓存市场参考估值"><p className="investment-detail__note">非账务数据，不构成当前持仓真值。</p><DetailGrid items={[[`行情价格 (${reference.quoteCurrency ?? detail.instrument.quoteCurrency})`, formatInvestmentMoney(reference.quotePrice, reference.quoteCurrency ?? detail.instrument.quoteCurrency)], ['行情时间', formatInvestmentDateTime(reference.quoteTime)], ['行情来源', reference.quoteProvider ?? '暂无'], ['汇率', reference.fxRate ?? '暂无'], ['市场参考估值', formatInvestmentMoney(reference.baseCurrencyValue, reference.baseCurrency)], ['估值新鲜度', investmentFreshnessLabel(reference.freshness)]]} />{investmentWarningMessages(reference.warnings).length > 0 && <ul className="investment-warnings">{investmentWarningMessages(reference.warnings).map(warning => <li key={warning}>{warning}</li>)}</ul>}</DetailSection>
    </article>;
}

function TransactionDetail({ detail, auditTimeline, auditTimelineLoading, onLoadAudit, writeDisabled, onCommand }: { detail: InvestmentTransactionDetail; auditTimeline: InvestmentAuditTimeline | null; auditTimelineLoading: boolean; onLoadAudit: () => void; writeDisabled: boolean; onCommand: (type: InvestmentCommandType) => void; }) {
    const correctionCopy = detail.correctionStatus === 'REVERSED' ? '此交易已冲正。原始事实保留用于审计，冲正通过追加不可变事实完成。' : detail.correctionStatus === 'REPLACED' ? '此交易已被替换。原始记录与替换后的有效记录分别保留。' : null;
    const eligible = detail.correctionStatus === 'UNCHANGED' && detail.transactionType !== 'OPENING_POSITION';
    return <article className="content-card investment-detail"><div className="section-heading"><div><p className="section-heading__eyebrow">LOGICAL TRANSACTION DETAIL</p><h2>投资交易详情</h2></div><Badge tone={badgeTone(detail.correctionStatus)}>{investmentCorrectionStatusLabel(detail.correctionStatus)}</Badge></div>{correctionCopy && <AlertMessage type="warning">{correctionCopy}</AlertMessage>}{eligible && <div className="data-table__actions"><button type="button" className="button button--danger button--small" disabled={writeDisabled} onClick={() => onCommand('REVERSAL')}>冲正交易</button><button type="button" className="button button--secondary button--small" disabled={writeDisabled} onClick={() => onCommand('REPLACEMENT')}>更正交易</button></div>}<DetailGrid items={[["交易类型", investmentTransactionTypeLabel(detail.transactionType)], ['生效交易时间', formatInvestmentDateTime(detail.effectiveTradeTime)], ['结算时间', formatInvestmentDateTime(detail.settlementTime)], ['账户', detail.account.displayName], ['标的', `${detail.instrument.name} (${detail.instrument.symbol})`], ['当前有效', detail.effective ? '是' : '否']]} />
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
