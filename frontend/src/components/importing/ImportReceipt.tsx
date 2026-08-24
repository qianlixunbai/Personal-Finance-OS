import { useState } from 'react';
import type { TransactionImportReceipt } from '../../types/transactionImport';
import { Pagination } from '../Pagination';

export function ImportReceipt({ receipt, recovered }: { receipt: TransactionImportReceipt; recovered: boolean }) {
    const [page, setPage] = useState(1); const size = 100; const totalPages = Math.max(1, Math.ceil(receipt.transactions.length / size)); const visible = receipt.transactions.slice((page - 1) * size, page * size);
    return <>
        <section className="import-preview-summary" aria-live="polite"><div><h2>权威导入回执</h2><p>{recovered ? '已找到已提交的权威回执。' : '导入已确认；以下数据来自服务端回执。'}</p></div><div className="summary-grid import-summary-grid"><Metric label="总行数" value={receipt.totalRows} /><Metric label="已创建" value={receipt.createdCount} /><Metric label="已跳过" value={receipt.skippedCount} /><Metric label="警告数" value={receipt.warningCount} /></div></section>
        <section className="page-panel import-panel"><h2>账户影响</h2><div className="import-table-scroll"><table className="data-table"><thead><tr><th scope="col">账户</th><th scope="col">行数</th><th scope="col">确认前余额</th><th scope="col">变化</th><th scope="col">确认后余额</th></tr></thead><tbody>{receipt.accountImpacts.map(impact => <tr key={impact.accountId}><td>Account #{impact.accountId}</td><td>{impact.rowCount}</td><td className="import-money">{impact.balanceBefore}</td><td className="import-money">{impact.delta}</td><td className="import-money">{impact.balanceAfter}</td></tr>)}</tbody></table></div></section>
        <section className="page-panel import-panel"><h2>创建的交易流水</h2><div className="import-table-scroll"><table className="data-table"><thead><tr><th scope="col">源行</th><th scope="col">交易 ID</th></tr></thead><tbody>{visible.map(item => <tr key={`${item.rowNumber}-${item.transactionId}`}><td>{item.rowNumber}</td><td>{item.transactionId}</td></tr>)}</tbody></table></div><Pagination page={page} totalPages={totalPages} summary={<>共 {receipt.transactions.length} 条引用</>} previousDisabled={page <= 1} nextDisabled={page >= totalPages} onPrevious={() => setPage(current => current - 1)} onNext={() => setPage(current => current + 1)} /></section>
        <section className="page-panel import-panel"><h2>审计详情</h2><dl className="import-confirm-summary"><div><dt>批次</dt><dd>{receipt.importBatchId}</dd></div><div><dt>确认时间</dt><dd>{receipt.confirmedAt}</dd></div><div><dt>来源文件</dt><dd>{receipt.sourceFileName}</dd></div><div><dt>Contract</dt><dd>{receipt.contractVersion}</dd></div><div><dt>结果摘要</dt><dd>{receipt.resultDigest}</dd></div><div><dt>文件摘要</dt><dd>{receipt.fileDigest}</dd></div></dl></section>
    </>;
}

function Metric({ label, value }: { label: string; value: number }) { return <div className="summary-card"><span>{label}</span><strong>{value}</strong></div>; }
