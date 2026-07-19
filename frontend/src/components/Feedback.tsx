import type { ReactNode } from 'react';
import { Icon } from './Visual';

type AlertType = 'error' | 'success' | 'warning';
const iconByType = { error: 'alert', success: 'check', warning: 'alert' } as const;
export function AlertMessage({ type, children }: { type: AlertType; children: ReactNode }) { return <div className={`alert alert--${type}`} role="alert"><Icon name={iconByType[type]} size={18} /><div>{children}</div></div>; }
export function EmptyState({ message }: { message: string }) { return <div className="empty-state">{message}</div>; }
export function EmptyTableRow({ colSpan, message }: { colSpan: number; message: string }) { return <tr><td colSpan={colSpan}><EmptyState message={message} /></td></tr>; }
