import type { ReactNode } from 'react';
import { EmptyState } from '../Feedback';

interface ChartCardProps {
    title: string;
    children: ReactNode;
    emptyMessage?: string;
}

export function ChartCard({ title, children, emptyMessage }: ChartCardProps) {
    return (
        <section style={{ background: '#fff', borderRadius: 12, padding: 24, boxShadow: '0 2px 12px rgba(0,0,0,.06)' }}>
            <h3 style={{ marginTop: 0 }}>{title}</h3>
            {emptyMessage ? <EmptyState message={emptyMessage} /> : children}
        </section>
    );
}
