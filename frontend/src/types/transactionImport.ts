export type ImportCanonicalTarget = 'date' | 'time' | 'type' | 'amount' | 'account' | 'category' | 'description' | 'currency';
export type ImportTransactionType = 'INCOME' | 'EXPENSE' | 'ADJUSTMENT';
export type ImportSessionStatus = 'MAPPING_REQUIRED' | 'PREVIEW_READY' | string;
export type ImportWorkflowState = 'IDLE' | 'FILE_SELECTED' | 'UPLOADING' | 'MAPPING_EDITING' | 'MAPPING_SUBMITTING' | 'MAPPING_DISCOVERY' | 'PREVIEW_READY';

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
    updatedAt: string;
}

export interface DiscoveredMappingValues {
    types: string[];
    accounts: string[];
    categories: string[];
}
