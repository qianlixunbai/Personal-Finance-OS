interface Props { state: string; busy: boolean; onRecover: () => Promise<void>; }

export function ImportRecoveryBanner({ state, busy, onRecover }: Props) {
    const message = state === 'AUTH_REQUIRED' ? '确认请求需要同一用户重新登录后恢复；不会生成新的确认意图。' : state === 'COMMITTED_AWAITING_RECEIPT' ? '写入结果已提交，正在只读查询权威回执。' : '确认结果暂时无法判定。请查询并恢复原确认，系统将先查询回执，再按需复用同一请求。';
    return <section className="page-panel import-panel" aria-live="polite"><h2>需要恢复确认结果</h2><p>{message}</p><button type="button" className="button button--primary" disabled={busy} onClick={() => void onRecover()}>{busy ? '正在恢复…' : '查询并恢复原确认'}</button></section>;
}
