package com.financeos.module.importing.dto;

import com.financeos.module.importing.preview.ImportPreviewException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionImportMappingTest {

    @Test
    void rejectsMappingsWhoseKeysCollideAfterUnicodeNormalizationAndTrimming() {
        assertThatThrownBy(() -> new TransactionImportMapping(Map.of(), Map.of(),
                Map.of(" Cash", 1L, "Cash", 2L), Map.of()))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("conflicting normalized keys");
    }
}
