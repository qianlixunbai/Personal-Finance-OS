import api from './index';
import type { CursorPage, InvestmentAuditTimeline, InvestmentCorrectionStatus, InvestmentPortfolio, InvestmentPositionDetail, InvestmentPositionListItem, InvestmentPositionStatus, InvestmentTransactionDetail, InvestmentTransactionListItem, InvestmentTransactionType } from '../types/investment';

type ApiEnvelope<T> = { data: T };
function query(params: object) {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => { if (value !== null && value !== undefined && value !== '') search.set(key, String(value)); });
    return search.toString();
}
async function get<T>(path: string, params?: object) {
    const suffix = params ? query(params) : '';
    const response = await api.get<ApiEnvelope<T>>(`${path}${suffix ? `?${suffix}` : ''}`);
    return response.data.data;
}

export interface InvestmentPositionQuery { status: InvestmentPositionStatus; cursor: string | null; size?: number; }
export interface InvestmentTransactionQuery { positionId?: number | null; type?: InvestmentTransactionType | ''; correctionStatus?: InvestmentCorrectionStatus | ''; from?: string; to?: string; cursor: string | null; size?: number; }

export const fetchInvestmentPortfolio = () => get<InvestmentPortfolio>('/investment/portfolio');
export const fetchInvestmentPositions = (params: InvestmentPositionQuery) => get<CursorPage<InvestmentPositionListItem>>('/investment/positions', params);
export const fetchInvestmentPosition = (positionId: number) => get<InvestmentPositionDetail>(`/investment/positions/${positionId}`);
export const fetchInvestmentTransactions = (params: InvestmentTransactionQuery) => get<CursorPage<InvestmentTransactionListItem>>('/investment/transactions', params);
export const fetchInvestmentTransaction = (logicalTransactionId: number) => get<InvestmentTransactionDetail>(`/investment/transactions/${logicalTransactionId}`);
export const fetchInvestmentAuditTimeline = (logicalTransactionId: number) => get<InvestmentAuditTimeline>(`/investment/transactions/${logicalTransactionId}/audit-timeline`);
