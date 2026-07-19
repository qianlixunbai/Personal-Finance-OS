import type { ReactNode } from 'react';
import { EmptyState } from '../Feedback';
interface ChartCardProps { title: string; children: ReactNode; emptyMessage?: string; }
export function ChartCard({ title, children, emptyMessage }: ChartCardProps) { return <section className="chart-card"><h2 className="chart-card__title">{title}</h2>{emptyMessage ? <EmptyState message={emptyMessage} /> : children}</section>; }
