import type { ReactNode } from 'react';

interface PaginationProps {
    page: number;
    totalPages: number;
    summary: ReactNode;
    previousDisabled: boolean;
    nextDisabled: boolean;
    onPrevious: () => void;
    onNext: () => void;
}

export function Pagination({
    page,
    totalPages,
    summary,
    previousDisabled,
    nextDisabled,
    onPrevious,
    onNext,
}: PaginationProps) {
    return (
        <div className="pagination">
            <span className="pagination__summary">第 {page} / {totalPages} 页，{summary}</span>
            <div className="pagination__actions">
                <button onClick={onPrevious} disabled={previousDisabled} className="pagination__button">上一页</button>
                <button onClick={onNext} disabled={nextDisabled} className="pagination__button">下一页</button>
            </div>
        </div>
    );
}
