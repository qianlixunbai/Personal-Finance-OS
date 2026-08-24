import type { ChangeEvent } from 'react';

function formatFileSize(size: number) { return size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KiB` : `${(size / (1024 * 1024)).toFixed(2)} MiB`; }

export function ImportUploadPanel({ file, busy, hasSession, onSelect, onUpload, onDiscard }: {
    file: { name: string; size: number; type: string } | null; busy: boolean; hasSession: boolean; onSelect: (file: File | null) => void; onUpload: () => void; onDiscard: () => void;
}) {
    const select = (event: ChangeEvent<HTMLInputElement>) => onSelect(event.target.files?.[0] ?? null);
    return <section className="page-panel import-panel" aria-labelledby="import-upload-title">
        <div className="section-heading"><div><p className="section-heading__eyebrow">STEP 01</p><h2 id="import-upload-title">上传文件</h2></div></div>
        <p className="import-copy">仅支持 CSV 和 XLSX，最大 5 MiB。文件内容、安全校验和格式判断均以后端为准。</p>
        {hasSession && <div className="import-session-note"><strong>当前已有导入会话。</strong><span>如需更换文件，请先放弃当前导入，避免误把新文件接到旧会话。</span><button type="button" className="button button--danger button--small" onClick={onDiscard} disabled={busy}>放弃当前导入</button></div>}
        <label className="import-file-picker" htmlFor="transaction-import-file">
            <span>选择 CSV 或 XLSX 文件</span><input id="transaction-import-file" type="file" accept=".csv,.xlsx" onChange={select} disabled={busy || hasSession} />
        </label>
        {file && <div className="import-file-meta" role="status"><strong>{file.name}</strong><span>{formatFileSize(file.size)} · {file.type || '未声明 MIME 类型'} · 本机选择信息</span><button type="button" className="button button--neutral button--small" onClick={() => onSelect(null)} disabled={busy}>清除</button></div>}
        <div className="data-table__actions"><button type="button" className="button button--primary" onClick={onUpload} disabled={busy || !file || hasSession}>{busy ? '正在上传并解析…' : '上传并开始映射'}</button></div>
    </section>;
}
