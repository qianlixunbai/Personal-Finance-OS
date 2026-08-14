package com.financeos.module.importing.preview;

import java.util.List;

public record SourceImportRow(int rowNumber, List<String> values) {
    public SourceImportRow {
        values = List.copyOf(values);
    }
}
