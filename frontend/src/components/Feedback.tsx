import type { ReactNode } from 'react';

type AlertType = 'error' | 'success' | 'warning';

const alertStyles: Record<AlertType, { background: string; color: string }> = {
    error: { background: '#f8d7da', color: '#721c24' },
    success: { background: '#d4edda', color: '#155724' },
    warning: { background: '#fff3cd', color: '#856404' },
};

export function AlertMessage({ type, children }: { type: AlertType; children: ReactNode }) {
    const colors = alertStyles[type];
    return (
        <div style={{ ...colors, padding: 12, borderRadius: 8, marginBottom: 16 }}>
            {children}
        </div>
    );
}

export function EmptyState({ message }: { message: string }) {
    return (
        <div style={{ textAlign: 'center', color: '#999', padding: 40 }}>
            {message}
        </div>
    );
}

export function EmptyTableRow({ colSpan, message }: { colSpan: number; message: string }) {
    return (
        <tr>
            <td colSpan={colSpan}>
                <EmptyState message={message} />
            </td>
        </tr>
    );
}
