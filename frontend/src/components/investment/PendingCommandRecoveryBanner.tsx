import type { PendingPresentation } from '../../hooks/useInvestmentCommandCoordinator';
export function PendingCommandRecoveryBanner({ pending, onRecover, onExport }: { pending: PendingPresentation | null; onRecover: () => void; onExport: () => void; }) {
    if (!pending) return null;
    const canRecover = pending.state === 'OUTCOME_UNKNOWN' || pending.state === 'SUCCEEDED_AWAITING_REFRESH' || pending.state === 'REJECTED_AWAITING_RECONCILE' || pending.state === 'AUTH_REQUIRED';
    const action = pending.continuation === 'RECONCILE_GET' || pending.state === 'REJECTED_AWAITING_RECONCILE' ? '重新核对当前账本' : pending.continuation === 'SUCCESS_REFRESH_GET' || pending.state === 'SUCCEEDED_AWAITING_REFRESH' ? '重新刷新' : '恢复原提交';
    return <div className="alert alert--warning investment-pending" role="alert"><strong>投资命令待处理：{pending.state}</strong><span>{pending.message}</span><div className="data-table__actions">{canRecover && <button type="button" className="button button--secondary button--small" onClick={onRecover}>{action}</button>}{pending.invalid && <button type="button" className="button button--secondary button--small" onClick={onExport}>导出 recovery evidence</button>}</div></div>;
}
