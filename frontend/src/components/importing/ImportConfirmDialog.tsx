import { useEffect, useRef, type KeyboardEvent } from 'react';
import type { TransactionImportPreviewResponse } from '../../types/transactionImport';

interface Props { preview: TransactionImportPreviewResponse; busy: boolean; onClose: () => void; onConfirm: () => Promise<void>; }

export function ImportConfirmDialog({ preview, busy, onClose, onConfirm }: Props) {
    const dialogRef = useRef<HTMLElement>(null);
    const cancelRef = useRef<HTMLButtonElement>(null);
    const returnFocusRef = useRef<HTMLElement | null>(null);

    useEffect(() => {
        returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        cancelRef.current?.focus();
        return () => {
            const returnFocus = returnFocusRef.current;
            if (returnFocus?.isConnected) returnFocus.focus();
        };
    }, []);

    const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
        if (event.key === 'Escape' && !busy) { event.preventDefault(); onClose(); return; }
        if (event.key !== 'Tab') return;
        const focusable = dialogRef.current?.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
        if (!focusable?.length) return;
        const first = focusable[0]; const last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };

    return <div className="command-modal-backdrop" role="presentation">
        <section ref={dialogRef} className="command-modal" role="dialog" aria-modal="true" aria-labelledby="import-confirm-title" aria-describedby="import-confirm-copy" aria-busy={busy} onKeyDown={onKeyDown}>
            <header><p className="section-heading__eyebrow">FINAL CONFIRMATION</p><h2 id="import-confirm-title">确认导入</h2></header>
            <p id="import-confirm-copy">本操作将按服务端冻结的 Preview 一次性写入。账户余额与最终结果仅以后端回执为准。</p>
            <dl className="import-confirm-summary">
                <div><dt>总行数</dt><dd>{preview.summary.totalRows}</dd></div><div><dt>有效行</dt><dd>{preview.summary.validRows}</dd></div>
                <div><dt>警告行</dt><dd>{preview.summary.warningRows}</dd></div><div><dt>阻塞错误</dt><dd>{preview.summary.errorRows}</dd></div>
                <div><dt>可导入行</dt><dd>{preview.summary.importableRows}</dd></div><div><dt>服务端到期时间</dt><dd>{preview.expiresAt}</dd></div>
            </dl>
            <p className="import-copy">不显示前端估算的余额变化或最终余额；这些数据只会在权威回执中展示。</p>
            <footer className="import-actions"><button ref={cancelRef} type="button" className="button button--secondary" onClick={onClose} disabled={busy}>返回预览</button><button type="button" className="button button--primary" onClick={() => void onConfirm()} disabled={busy} aria-label={`确认导入 ${preview.summary.importableRows} 条流水`}>{busy ? '正在安全确认…' : `确认导入 ${preview.summary.importableRows} 条流水`}</button></footer>
        </section>
    </div>;
}
