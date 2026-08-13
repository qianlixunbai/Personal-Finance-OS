export type InvestmentCommandType = 'FIRST_BUY' | 'BUY' | 'SELL' | 'DIVIDEND' | 'REVERSAL' | 'REPLACEMENT';
export type ReplacementTransactionType = 'BUY' | 'SELL' | 'DIVIDEND';
export type PendingCommandState = 'SUBMITTING' | 'OUTCOME_UNKNOWN' | 'RECOVERING' | 'AUTH_REQUIRED' | 'SUCCEEDED_AWAITING_REFRESH' | 'REJECTED_AWAITING_RECONCILE';
export type ResumeState = 'SUBMITTING' | 'OUTCOME_UNKNOWN' | 'SUCCEEDED_AWAITING_REFRESH' | 'REJECTED_AWAITING_RECONCILE';
export type InvestmentCommandUiState = 'DRAFT' | 'CONFIRMING' | PendingCommandState | 'REJECTED' | 'RECOVERY_RECORD_INVALID';

export interface PendingInvestmentCommandV1 {
    schemaVersion: 1;
    userId: number;
    state: PendingCommandState;
    resumeState?: ResumeState;
    commandType: InvestmentCommandType;
    method: 'POST';
    path: string;
    idempotencyKey: string;
    bodyJson: string;
    /** Immutable subtype evidence for a REPLACEMENT command; never inferred from editable form data. */
    originalTransactionType?: ReplacementTransactionType;
    /** Immutable user-intent subtype for a REPLACEMENT command; never inferred from editable form data. */
    replacementRequestType?: ReplacementTransactionType;
    target: { assetId?: number; logicalTransactionId?: number; accountId?: number; instrumentId?: number; };
    refresh?: { assetId: number; logicalTransactionId: number; };
    submittedAt: string;
    updatedAt: string;
}

export interface InvestmentCommandReceipt {
    commandType: InvestmentCommandType;
    transactionType?: string;
    cashDelta?: string;
    balanceAfter?: string;
    createdAt?: string;
    idempotentReplay: boolean;
}

export interface InvestmentCommandDraft {
    commandType: InvestmentCommandType;
    replacementTransactionType?: ReplacementTransactionType;
    replacementRequestType?: ReplacementTransactionType;
    assetId?: number;
    logicalTransactionId?: number;
    accountId?: number;
    instrumentId?: number;
    quantity: string;
    unitPrice: string;
    grossAmount: string;
    feeAmount: string;
    taxAmount: string;
    externalReference: string;
    note: string;
    reason: string;
}
