import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { fetchTransactionImportReceipt } from '../api/transactionImport';
import { AlertMessage } from '../components/Feedback';
import { ImportReceipt } from '../components/importing/ImportReceipt';
import { PageHeader } from '../components/PageHeader';
import type { TransactionImportReceipt as Receipt } from '../types/transactionImport';
import { writeTransactionImportReceiptReturn } from '../utils/transactionImportStorage';

export default function TransactionImportReceipt() {
    const { batchId = '' } = useParams(); const [receipt, setReceipt] = useState<Receipt | null>(null); const [loading, setLoading] = useState(true); const [error, setError] = useState('');
    useEffect(() => { let active = true; setLoading(true); setError(''); fetchTransactionImportReceipt(batchId, () => { const userId = Number(localStorage.getItem('finance-os:auth-user-id:v1')); writeTransactionImportReceiptReturn(sessionStorage, userId, batchId); }).then(next => { if (active) setReceipt(next); }).catch(requestError => { if (!active) return; const status = (requestError as { response?: { status?: number } }).response?.status; setError(status === 404 || status === 403 ? '回执不存在或不可见。' : '无法加载权威回执，请稍后重试。'); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [batchId]);
    return <div className="import-workspace"><PageHeader title="导入回执" subtitle="回执每次进入均由服务端权威接口重新读取。" />{loading && <p role="status">正在加载权威回执…</p>}{error && <AlertMessage type="error">{error}</AlertMessage>}{receipt && <ImportReceipt receipt={receipt} recovered />}</div>;
}
