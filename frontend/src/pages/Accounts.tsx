import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import api from '../api';
import { AlertMessage, EmptyTableRow } from '../components/Feedback';
import { getErrorMessage } from '../utils/error';
import { formatCurrency } from '../utils/format';

interface Account {
    id: number;
    name: string;
    type: string;
    currency: string;
    balance: number;
    status: string;
}

interface PageResult<T> {
    records: T[];
    total: number;
    page: number;
    size: number;
}

const pageSize = 20;

export default function Accounts() {
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [error, setError] = useState('');

    const fetchPage = async (targetPage = page) => {
        const res = await api.get('/accounts/page', { params: { page: targetPage, size: pageSize } });
        const data: PageResult<Account> = res.data.data;
        setAccounts(data.records || []);
        setTotal(data.total || 0);
        setPage(data.page || targetPage);
    };

    useEffect(() => {
        fetchPage(1).catch(err => setError(getErrorMessage(err, '账户列表加载失败')));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    const hasNextPage = page < totalPages && accounts.length >= pageSize;

    return (
        <div>
            <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
                <h2>账户管理</h2>
            </div>

            {error && <AlertMessage type="error">{error}</AlertMessage>}

            <div className="table-scroll">
                <table style={{ width: '100%', borderCollapse: 'collapse', background: '#fff', borderRadius: 12, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
                    <thead>
                        <tr>
                            <th style={thStyle}>名称</th>
                            <th style={thStyle}>类型</th>
                            <th style={thStyle}>状态</th>
                            <th style={thStyle}>余额</th>
                        </tr>
                    </thead>
                    <tbody>
                        {accounts.map(account => (
                            <tr key={account.id}>
                                <td style={tdStyle}>{account.name}</td>
                                <td style={tdStyle}>{account.type}</td>
                                <td style={tdStyle}>{account.status}</td>
                                <td style={{ ...tdStyle, fontWeight: 600, color: account.balance >= 0 ? '#00b894' : '#e17055' }}>{formatCurrency(account.balance)}</td>
                            </tr>
                        ))}
                        {accounts.length === 0 && <EmptyTableRow colSpan={4} message="暂无账户" />}
                    </tbody>
                </table>
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 16 }}>
                <span style={{ color: '#636e72' }}>第 {page} / {totalPages} 页，共 {total} 个账户</span>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={() => fetchPage(page - 1)} disabled={page <= 1} style={pageButtonStyle}>上一页</button>
                    <button onClick={() => fetchPage(page + 1)} disabled={!hasNextPage} style={pageButtonStyle}>下一页</button>
                </div>
            </div>
        </div>
    );
}

const thStyle: CSSProperties = {
    padding: '12px 16px',
    textAlign: 'left',
    borderBottom: '1px solid #eee',
};

const tdStyle: CSSProperties = {
    padding: '12px 16px',
    borderBottom: '1px solid #eee',
};

const pageButtonStyle: CSSProperties = {
    padding: '8px 14px',
    background: '#fff',
    border: '1px solid #ddd',
    borderRadius: 8,
    cursor: 'pointer',
};
