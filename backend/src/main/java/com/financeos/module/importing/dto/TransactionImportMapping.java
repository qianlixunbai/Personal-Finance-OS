package com.financeos.module.importing.dto;

import com.financeos.module.importing.preview.ImportPreviewException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.text.Normalizer;

public record TransactionImportMapping(Map<String, String> columnMappings,
                                       Map<String, String> typeMappings,
                                       Map<String, Long> accountMappings,
                                       Map<String, Long> categoryMappings) {
    public TransactionImportMapping {
        columnMappings = normalize(columnMappings);
        typeMappings = normalize(typeMappings);
        accountMappings = normalize(accountMappings);
        categoryMappings = normalize(categoryMappings);
    }

    private static <T> Map<String, T> normalize(Map<String, T> values) {
        if (values == null) return Map.of();
        Map<String, T> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null) throw new ImportPreviewException(400, "Import mapping keys must not be null");
            String normalizedKey = Normalizer.normalize(key, Normalizer.Form.NFC).trim();
            if (normalized.containsKey(normalizedKey)) {
                throw new ImportPreviewException(400, "Import mapping contains conflicting normalized keys");
            }
            normalized.put(normalizedKey, value);
        });
        return Map.copyOf(normalized);
    }
}
