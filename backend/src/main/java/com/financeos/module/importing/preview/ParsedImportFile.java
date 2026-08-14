package com.financeos.module.importing.preview;

import java.util.List;

public record ParsedImportFile(List<String> headers, List<SourceImportRow> rows) {
    public ParsedImportFile {
        headers = List.copyOf(headers);
        rows = List.copyOf(rows);
    }
}
