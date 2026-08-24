import { Pagination } from '../Pagination';
import type { TransactionImportPreviewResponse, TransactionImportPreviewRow } from '../../types/transactionImport';
import { previewRowStatus, type ImportWarningMetadata } from '../../utils/transactionImportPreview';

interface Props {
    preview: TransactionImportPreviewResponse;
    rows: TransactionImportPreviewRow[];
    page: number;
    pageLast: boolean;
    busy: boolean;
    workflow: string;
    warnings: ImportWarningMetadata[];
    acknowledged: Set<string>;
    hydrationError: string;
    readyForConfirm: boolean;
    onPage: (page: number) => Promise<void>;
    onBackToMapping: () => void;
    onRetryHydration: () => void;
    onToggleWarningGroup: (code: string, checked: boolean) => void;
    onRepreview: () => Promise<void>;
}

const labels: Record<string, string> = { INVALID_TRANSACTION_TYPE: '交易类型无效', INVALID_AMOUNT: '金额格式无效', INVALID_DATE: '日期或时间无效', UNSUPPORTED_CURRENCY: '币种不受支持', MISSING_REQUIRED_FIELD: '缺少必填字段', ACCOUNT_NOT_MAPPED: '账户未映射', ACCOUNT_NOT_FOUND: '账户不存在或不可见', ACCOUNT_INACTIVE: '账户未启用', CATEGORY_NOT_MAPPED: '分类未映射', CATEGORY_TYPE_MISMATCH: '分类与交易类型不匹配', IN_FILE_PROBABLE: '文件内可能重复', DATABASE_PROBABLE: '与已有流水可能重复' };
const statusText = (row: TransactionImportPreviewRow) => ({ ERROR: '错误（阻塞）', WARNING: '警告（需复核）', VALID: '有效' })[previewRowStatus(row)];
function text(values: Record<string, string>, ...keys: string[]) { for (const key of keys) if (values[key]) return values[key]; return '—'; }

export function ImportPreviewPanel({ preview, rows, page, pageLast, busy, workflow, warnings, acknowledged, hydrationError, readyForConfirm, onPage, onBackToMapping, onRetryHydration, onToggleWarningGroup, onRepreview }: Props) {
    const totalPages = Math.max(1, Math.ceil(preview.summary.totalRows / 100));
    const groups = [...new Set(warnings.map(warning => warning.code))].map(code => ({ code, items: warnings.filter(warning => warning.code === code) }));
    const expired = workflow === 'EXPIRED'; const cancelled = workflow === 'CANCELLED'; const recovery = workflow === 'RECOVERY_REQUIRED';
    if (expired || cancelled || recovery) return <section className="page-panel import-panel" aria-live="polite"><h2>{expired ? '预览已过期' : cancelled ? '导入已取消' : '需要重新生成预览'}</h2><p className="import-copy">{expired ? '当前 Preview 不能继续使用，请重新开始导入。' : cancelled ? '此会话已进入终态，不能继续预览或确认。' : '当前状态无法安全继续。重新生成会创建新的服务端 revision 并使旧警告确认失效。'}</p>{recovery && <button type="button" className="button button--primary" disabled={busy} onClick={() => void onRepreview()}>重新生成预览</button>}</section>;
    return <>
        <section className="import-preview-summary" aria-labelledby="import-preview-title">
            <div><h2 id="import-preview-title">服务端预览</h2><p>Revision {preview.revision} · {busy ? '正在加载 Preview' : '后端行数据与汇总'}</p></div>
            <div className="summary-grid import-summary-grid"><Metric label="总行数" value={preview.summary.totalRows} /><Metric label="有效行" value={preview.summary.validRows} /><Metric label="警告行" value={preview.summary.warningRows} /><Metric label="错误行" value={preview.summary.errorRows} /></div>
            <div className="import-actions"><button type="button" className="button button--secondary" disabled={busy} onClick={onBackToMapping}>返回映射</button></div>
        </section>
        <section className="page-panel import-panel" aria-busy={busy} aria-live="polite"><div className="section-heading"><div><p className="section-heading__eyebrow">SERVER-PAGED ROWS</p><h2>预览行</h2></div><span className="import-copy">当前仅保留第 {page} 页；完整扫描只保留问题元数据。</span></div>
            <div className="import-table-scroll"><table className="data-table import-preview-table"><thead><tr><th scope="col">行</th><th scope="col">状态</th><th scope="col">日期 / 类型</th><th scope="col">金额</th><th scope="col">账户 / 分类</th><th scope="col">说明</th><th scope="col">问题</th></tr></thead><tbody>{rows.map(row => <tr key={row.rowNumber}><td>#{row.rowNumber}</td><td><span className={`badge badge--${previewRowStatus(row).toLowerCase()}`}>{statusText(row)}</span></td><td>{text(row.normalizedValues, 'date', 'time')}<br /><span>{text(row.normalizedValues, 'type')}</span></td><td className="import-money">{text(row.normalizedValues, 'amount')}</td><td>{text(row.normalizedValues, 'account')}<br /><span>{text(row.normalizedValues, 'category')}</span></td><td>{text(row.normalizedValues, 'description')}</td><td><IssueList row={row} /></td></tr>)}</tbody></table></div>
            {!busy && rows.length === 0 && <p className="empty-state">此页没有 Preview 行。</p>}
            <Pagination page={page} totalPages={totalPages} summary={<>共 {preview.summary.totalRows} 行</>} previousDisabled={busy || page <= 1} nextDisabled={busy || pageLast || page >= totalPages} onPrevious={() => void onPage(page - 1)} onNext={() => void onPage(page + 1)} />
        </section>
        <section className="page-panel import-panel" aria-labelledby="import-warning-review"><h2 id="import-warning-review">警告复核</h2><p className="import-copy">以下内容完全来自服务端 Preview；每个分组都需要显式确认。错误行不能通过确认警告绕过。</p>{workflow === 'WARNING_HYDRATING' && <p role="status">正在扫描全部 Preview 页以加载完整警告证据……</p>}{hydrationError && <div className="import-inline-error" role="alert">{hydrationError} <button type="button" className="button button--secondary" onClick={onRetryHydration}>重新扫描</button></div>}{groups.map(group => { const allAcknowledged = group.items.every(item => acknowledged.has(item.id)); return <label className="import-warning-group" key={group.code}><input type="checkbox" checked={allAcknowledged} onChange={event => onToggleWarningGroup(group.code, event.target.checked)} disabled={busy || Boolean(hydrationError)} /><span><strong>我已复核“{labels[group.code] ?? `服务端警告 ${group.code}`}”的 {group.items.length} 项警告</strong><small>{group.items.map(item => `第 ${item.rowNumber ?? '—'} 行：${item.message} (${item.code})`).join('；')}</small></span></label>; })}{!busy && !hydrationError && groups.length === 0 && <p role="status">服务端未返回需要确认的警告。</p>}
            {preview.summary.errorRows > 0 && <p className="import-inline-error" role="alert">存在 {preview.summary.errorRows} 行阻塞错误。请返回映射或修正源文件后重新生成 Preview。</p>}
            {readyForConfirm && <div className="import-ready-boundary" role="status"><strong>Ready to confirm</strong><span>本阶段仅到达 UI 边界；未展示 Confirm 控件，也不会发起 Confirm 请求。</span></div>}
        </section>
    </>;
}

function Metric({ label, value }: { label: string; value: number }) { return <div className="summary-card"><span>{label}</span><strong>{value}</strong></div>; }
function IssueList({ row }: { row: TransactionImportPreviewRow }) { const issues = [...row.errors, ...row.warnings]; return issues.length ? <ul className="import-issue-list">{issues.map(issue => <li key={issue.id}><strong>{labels[issue.code] ?? issue.code}</strong><span>{issue.field ? `${issue.field}：` : ''}{issue.message} · {issue.code}</span></li>)}</ul> : <span>—</span>; }
