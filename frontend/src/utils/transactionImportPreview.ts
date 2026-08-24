import type { TransactionImportPreviewRow, TransactionImportValidationMessage } from '../types/transactionImport';

export type PreviewRowStatus = 'ERROR' | 'WARNING' | 'VALID';

export interface ImportWarningMetadata {
    id: string;
    code: string;
    rowNumber: number | null;
    field: string | null;
    message: string;
    page: number;
}

export function previewRowStatus(row: TransactionImportPreviewRow): PreviewRowStatus {
    return row.errors.length > 0 ? 'ERROR' : row.warnings.length > 0 ? 'WARNING' : 'VALID';
}

export function isLastPreviewPage(rowCount: number, requestedSize: number) { return rowCount < requestedSize; }

function warningMetadata(warning: TransactionImportValidationMessage, fallbackRowNumber: number, page: number): ImportWarningMetadata {
    return { id: warning.id, code: warning.code, rowNumber: warning.rowNumber ?? fallbackRowNumber, field: warning.field, message: warning.message, page };
}

export function collectWarningMetadata(rows: TransactionImportPreviewRow[], page: number): ImportWarningMetadata[] {
    const found = new Map<string, ImportWarningMetadata>();
    for (const row of rows) for (const warning of row.warnings) {
        if (warning.id && !found.has(warning.id)) found.set(warning.id, warningMetadata(warning, row.rowNumber, page));
    }
    return [...found.values()].sort((left, right) => left.id.localeCompare(right.id));
}

export function requiredWarningIds(warnings: ImportWarningMetadata[], acknowledged: Set<string>) {
    const ids = [...new Set(warnings.map(warning => warning.id))].sort();
    return ids.every(id => acknowledged.has(id)) ? ids : [];
}
