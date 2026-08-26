export type ImportCanonicalTarget = 'date' | 'time' | 'type' | 'amount' | 'account' | 'category' | 'description' | 'currency';
export type ImportTransactionType = 'INCOME' | 'EXPENSE' | 'ADJUSTMENT';
export type ImportSessionStatus = 'MAPPING_REQUIRED' | 'PREVIEW_READY' | string;
export type ImportWorkflowState = 'IDLE' | 'FILE_SELECTED' | 'UPLOADING' | 'MAPPING_EDITING' | 'MAPPING_SUBMITTING' | 'MAPPING_DISCOVERY' | 'PREVIEW_LOADING' | 'WARNING_HYDRATING' | 'WARNING_REVIEW' | 'READY_FOR_CONFIRM' | 'CONFIRMING' | 'UNKNOWN_OUTCOME' | 'RECOVERING' | 'CONFIRMED' | 'RECEIPT_LOADING' | 'RECEIPT_READY' | 'CONFLICT' | 'INCONSISTENT' | 'EXPIRED' | 'CANCELLED' | 'RECOVERY_REQUIRED';

export type DecimalText = string;

export interface TransactionImportReceipt {
    importSessionId: string;
    importBatchId: string;
    status: 'CONFIRMED';
    sourceFileName: string;
    fileDigest: string;
    totalRows: number;
    acceptedRows: number;
    createdCount: number;
    skippedCount: number;
    warningCount: number;
    transactions: Array<{ rowNumber: number; transactionId: number }>;
    accountImpacts: Array<{ accountId: number; rowCount: number; balanceBefore: DecimalText; delta: DecimalText; balanceAfter: DecimalText }>;
    confirmedAt: string;
    contractVersion: string;
    resultDigest: string;
}

export interface TransactionImportConfirmResponse { receipt: TransactionImportReceipt; idempotentReplay: boolean; }

export type PendingTransactionImportState = 'SUBMITTING' | 'OUTCOME_UNKNOWN' | 'RECOVERING_LOOKUP' | 'RECOVERING_CONFIRM' | 'COMMITTED_AWAITING_RECEIPT' | 'AUTH_REQUIRED';
export type PendingTransactionImportResumeState = 'SUBMITTING' | 'OUTCOME_UNKNOWN' | 'COMMITTED_AWAITING_RECEIPT';

export interface PendingTransactionImportV1 {
    schemaVersion: 1;
    userId: number;
    state: PendingTransactionImportState;
    resumeState?: PendingTransactionImportResumeState;
    method: 'POST';
    path: string;
    importSessionId: string;
    importBatchId: string;
    idempotencyKey: string;
    bodyJson: string;
    submittedAt: string;
    updatedAt: string;
}

export type TransactionImportColumnSelection = Record<ImportCanonicalTarget, string>;

export interface TransactionImportMapping {
    columnMappings: Record<string, string>;
    typeMappings: Record<string, ImportTransactionType>;
    accountMappings: Record<string, number>;
    categoryMappings: Record<string, number>;
}

export interface TransactionImportSummary {
    totalRows: number;
    validRows: number;
    warningRows: number;
    errorRows: number;
    importableRows: number;
    duplicateCandidates: number;
}

export interface TransactionImportValidationMessage {
    id: string;
    code: string;
    scope: string;
    field: string | null;
    rowNumber: number | null;
    message: string;
    retryable: boolean;
}

export interface TransactionImportPreviewRow {
    rowNumber: number;
    sourceValues: Record<string, string>;
    normalizedValues: Record<string, string>;
    mappingStatus: string;
    errors: TransactionImportValidationMessage[];
    warnings: TransactionImportValidationMessage[];
    duplicateStatus: string;
    importable: boolean;
}

export interface TransactionImportPreviewResponse {
    importSessionId: string;
    importBatchId: string | null;
    sessionStatus: ImportSessionStatus;
    revision: number;
    detectedColumns: string[];
    mapping: TransactionImportMapping | null;
    rows: TransactionImportPreviewRow[];
    summary: TransactionImportSummary;
    fileDigest: string;
    mappingDigest: string | null;
    optionsDigest: string;
    normalizedRowsDigest: string | null;
    expiresAt: string;
    confirmable: boolean;
    previewToken: string | null;
}

export interface ImportAccountOption { id: number; name: string; type: string; status: string; currency?: string; }
export interface ImportCategoryOption { id: number; name: string; type: string; }

export interface TransactionImportDraftSnapshot {
    schemaVersion: 1;
    userId: number;
    activeStep: 'MAPPING' | 'PREVIEW_READY';
    file: { name: string; size: number; type: string } | null;
    sessionId: string;
    batchId: string | null;
    sessionStatus: ImportSessionStatus;
    revision: number;
    detectedColumns: string[];
    mapping: TransactionImportMapping | null;
    summary: TransactionImportSummary | null;
    fileDigest: string;
    mappingDigest: string | null;
    optionsDigest: string;
    normalizedRowsDigest: string | null;
    expiresAt: string;
    previewToken: string | null;
    acknowledgedWarningIds?: string[];
    updatedAt: string;
}

export interface DiscoveredMappingValues {
    types: string[];
    accounts: string[];
    categories: string[];
}
