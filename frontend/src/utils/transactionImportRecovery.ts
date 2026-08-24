export type ReceiptLookupRecoveryAction = 'RECEIPT_READY' | 'RETRY_SAME_CONFIRM' | 'AUTH_REQUIRED' | 'RETAIN_UNKNOWN';

export type ConfirmDomainAction =
    | 'RETURN_TO_PREVIEW'
    | 'RESTART_IMPORT'
    | 'TERMINAL_EXPIRED'
    | 'TERMINAL_CANCELLED'
    | 'REPREVIEW'
    | 'RETURN_TO_WARNING_REVIEW'
    | 'BLOCK_EXACT_DUPLICATE'
    | 'RECONCILE_RECEIPT'
    | 'MANUAL_RESOLUTION'
    | 'INCONSISTENT'
    | 'RETRY_SAME_CONFIRM'
    | 'RETAIN_UNKNOWN';

const initialConfirmActions: Readonly<Record<string, ConfirmDomainAction>> = {
    IMPORT_CONFIRM_REQUEST_INVALID: 'RETURN_TO_PREVIEW',
    IMPORT_SESSION_NOT_FOUND: 'RESTART_IMPORT',
    IMPORT_PREVIEW_EXPIRED: 'TERMINAL_EXPIRED',
    IMPORT_SESSION_CANCELLED: 'TERMINAL_CANCELLED',
    IMPORT_PREVIEW_STALE: 'REPREVIEW',
    IMPORT_PREVIEW_UNAVAILABLE: 'REPREVIEW',
    IMPORT_DUPLICATE_EVIDENCE_CHANGED: 'REPREVIEW',
    IMPORT_WARNING_ACK_REQUIRED: 'RETURN_TO_WARNING_REVIEW',
    IMPORT_EXACT_DUPLICATE: 'BLOCK_EXACT_DUPLICATE',
    IMPORT_BATCH_ALREADY_CONFIRMED: 'RECONCILE_RECEIPT',
    IMPORT_IDEMPOTENCY_CONFLICT: 'MANUAL_RESOLUTION',
    IMPORT_LOCK_CONFLICT: 'RECONCILE_RECEIPT',
    IMPORT_CONFIRM_INCONSISTENT: 'INCONSISTENT',
    IMPORT_BATCH_NOT_FOUND: 'RETRY_SAME_CONFIRM',
};

export function confirmDomainAction(errorCode: string | undefined, recovery: boolean): ConfirmDomainAction {
    if (errorCode === 'IMPORT_BATCH_NOT_FOUND') return 'RETRY_SAME_CONFIRM';
    if (recovery) return 'RETAIN_UNKNOWN';
    return errorCode ? initialConfirmActions[errorCode] ?? 'RETAIN_UNKNOWN' : 'RETAIN_UNKNOWN';
}

export function recoveryActionForReceiptLookup(status: number): ReceiptLookupRecoveryAction {
    if (status === 200) return 'RECEIPT_READY';
    if (status === 404) return 'RETRY_SAME_CONFIRM';
    if (status === 401) return 'AUTH_REQUIRED';
    return 'RETAIN_UNKNOWN';
}
