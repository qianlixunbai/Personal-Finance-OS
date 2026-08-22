package com.financeos.module.importing.preview;

import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRow;
import com.financeos.module.importing.dto.TransactionImportPreviewSummary;

import java.util.List;

public record TransactionImportPreviewPlan(List<String> headers, TransactionImportMapping mapping,
                                           List<TransactionImportPreviewRow> rows,
                                           TransactionImportPreviewSummary summary) {
    public TransactionImportPreviewPlan {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }
}
