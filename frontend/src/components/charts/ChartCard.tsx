import type { ReactNode } from 'react';
import { EmptyState } from '../Feedback';
interface ChartCardProps { title: string; children: ReactNode; emptyMessage?: string; className?: string; meta?: ReactNode; emptyAction?: ReactNode; }
export function ChartCard({ title, children, emptyMessage, className = '', meta, emptyAction }: ChartCardProps) { return <section className={`chart-card ${className}`}><header className="chart-card__header"><h2 className="chart-card__title">{title}</h2>{meta && <div className="chart-card__meta">{meta}</div>}</header>{emptyMessage ? <EmptyState compact message={emptyMessage} action={emptyAction} /> : children}</section>; }
