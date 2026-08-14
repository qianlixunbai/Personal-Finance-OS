package com.financeos.module.importing.preview;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvTransactionImportParserTest {

    @Test
    void parsesUtf8BomQuotedValuesAndPhysicalRowNumbers() {
        String csv = "\uFEFFdate,description\r\n2026-01-02,\"coffee, \"\"large\"\"\"\r\n\r\n2026-01-03,tea\r\n";

        ParsedImportFile result = new CsvTransactionImportParser().parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.headers()).containsExactly("date", "description");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0).rowNumber()).isEqualTo(2);
        assertThat(result.rows().get(0).values()).containsExactly("2026-01-02", "coffee, \"large\"");
        assertThat(result.rows().get(1).rowNumber()).isEqualTo(4);
    }

    @Test
    void rejectsMalformedUtf8InsteadOfFallingBackToPlatformEncoding() {
        byte[] malformed = new byte[] {'d', 'a', 't', 'e', '\n', (byte) 0xC3, 0x28};

        assertThatThrownBy(() -> new CsvTransactionImportParser().parse(new ByteArrayInputStream(malformed)))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("UTF-8");
    }
}
