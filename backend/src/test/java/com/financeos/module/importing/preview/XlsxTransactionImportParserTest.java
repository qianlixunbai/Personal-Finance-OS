package com.financeos.module.importing.preview;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxTransactionImportParserTest {

    @Test
    void parsesOneVisibleWorksheetAndPreservesRowNumbers() throws Exception {
        byte[] workbook = workbookBytes();

        ParsedImportFile result = new XlsxTransactionImportParser().parse(new ByteArrayInputStream(workbook));

        assertThat(result.headers()).containsExactly("date", "amount");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().rowNumber()).isEqualTo(2);
        assertThat(result.rows().getFirst().values()).containsExactly("2026-01-02", "12.50");
    }

    @Test
    void rejectsFormulaCellsWithoutEvaluatingTheirCachedValue() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("transactions");
            sheet.createRow(0).createCell(0).setCellValue("amount");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            workbook.write(output);

            assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(output.toByteArray())))
                    .isInstanceOf(ImportPreviewException.class)
                    .hasMessageContaining("unsupported cell type");
        }
    }

    @Test
    void formatsDateCellsAsIsoDatesForPreviewValidation() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("transactions");
            sheet.createRow(0).createCell(0).setCellValue("date");
            var dateCell = sheet.createRow(1).createCell(0);
            dateCell.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 1, 2)));
            dateCell.setCellStyle(workbook.createCellStyle());
            short format = workbook.createDataFormat().getFormat("yyyy-mm-dd");
            dateCell.getCellStyle().setDataFormat(format);
            workbook.write(output);

            ParsedImportFile result = new XlsxTransactionImportParser().parse(new ByteArrayInputStream(output.toByteArray()));

            assertThat(result.rows().getFirst().values()).containsExactly("2026-01-02");
        }
    }

    @Test
    void formatsTimeCellsAsIsoTimesForPreviewValidation() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("transactions");
            sheet.createRow(0).createCell(0).setCellValue("time");
            var timeCell = sheet.createRow(1).createCell(0);
            timeCell.setCellValue(java.sql.Time.valueOf(LocalTime.of(13, 45)));
            timeCell.setCellStyle(workbook.createCellStyle());
            short format = workbook.createDataFormat().getFormat("hh:mm");
            timeCell.getCellStyle().setDataFormat(format);
            workbook.write(output);

            ParsedImportFile result = new XlsxTransactionImportParser().parse(new ByteArrayInputStream(output.toByteArray()));

            assertThat(result.rows().getFirst().values()).containsExactly("13:45");
        }
    }

    @Test
    void rejectsDateCellsWhoseNonZeroTimeWouldOtherwiseBeSilentlyDiscarded() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("transactions");
            sheet.createRow(0).createCell(0).setCellValue("date");
            var dateTimeCell = sheet.createRow(1).createCell(0);
            dateTimeCell.setCellValue(java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 13, 45)));
            dateTimeCell.setCellStyle(workbook.createCellStyle());
            dateTimeCell.getCellStyle().setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            workbook.write(output);

            assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(output.toByteArray())))
                    .isInstanceOf(ImportPreviewException.class)
                    .hasMessageContaining("date-time cells");
        }
    }

    @Test
    void rejectsExternalLinksAndXmlEntityExpansionBeforeOpeningTheWorkbook() throws Exception {
        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip(
                "xl/_rels/workbook.xml.rels", "<Relationship TargetMode=\"External\" Target=\"https://example.com\"/>"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX content");
        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip(
                "xl/workbook.xml", "<!DOCTYPE workbook [<!ENTITY xxe SYSTEM \"file:///secret\">]>"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX content");
        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip(
                "xl/worksheets/sheet1.xml", "<xi:include href=\"https://example.com\"/>"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX content");
    }

    @Test
    void rejectsMacroEmbeddedObjectsAndArchivePathTraversal() throws Exception {
        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip("xl/vbaProject.bin", "macro"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX archive");
        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip("xl/embeddings/oleObject1.bin", "object"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX archive");
    }

    @Test
    void rejectsCompressedEntriesThatExceedTheExpandedSizeLimit() throws Exception {
        byte[] payload = new byte[20 * 1024 * 1024 + 1];
        Arrays.fill(payload, (byte) 0);

        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip("xl/sharedStrings.xml", payload))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX archive");
        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip("../outside.xml", "payload"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX archive");
    }

    @Test
    void rejectsHighCompressionRatioEntriesEvenWhenTheyAreNotReferencedByTheWorkbook() throws Exception {
        byte[] payload = new byte[1024 * 1024];
        Arrays.fill(payload, (byte) 0);

        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip("unused.bin", payload))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX archive");
    }

    @Test
    void rejectsXmlThatExceedsTheContractualAttributeBoundBeforeWorkbookParsing() throws Exception {
        String attributes = java.util.stream.IntStream.range(0, 65)
                .mapToObj(index -> " a" + index + "=\"value\"")
                .collect(java.util.stream.Collectors.joining());

        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip(
                "xl/workbook.xml", "<workbook" + attributes + "/>"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("XML resource limit");
    }

    @Test
    void rejectsXmlThatExceedsTheContractualNestingBoundBeforeWorkbookParsing() throws Exception {
        String nested = "<a>".repeat(65) + "</a>".repeat(65);

        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip(
                "xl/workbook.xml", nested))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("XML resource limit");
    }

    @Test
    void rejectsXmlThatExceedsTheContractualNodeBoundBeforeWorkbookParsing() throws Exception {
        String nodes = "<root>" + java.util.stream.IntStream.range(0, 1_000_001)
                .mapToObj(index -> "<node id=\"" + index + "\"/>")
                .collect(java.util.stream.Collectors.joining()) + "</root>";

        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(zip(
                "xl/workbook.xml", nodes))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("XML resource limit");
    }

    @Test
    void rejectsDuplicateCriticalOoxmlParts() throws Exception {
        assertThatThrownBy(() -> new XlsxTransactionImportParser().parse(new ByteArrayInputStream(duplicateZip(
                "xl/workbook.xml", "<workbook/>", "<workbook/>"))))
                .isInstanceOf(ImportPreviewException.class)
                .hasMessageContaining("Unsafe XLSX archive");
    }

    private byte[] zip(String entryName, String content) throws Exception {
        return zip(entryName, content.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] zip(String entryName, byte[] content) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content);
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }

    private byte[] workbookBytes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("transactions");
            sheet.createRow(0).createCell(0).setCellValue("date");
            sheet.getRow(0).createCell(1).setCellValue("amount");
            sheet.createRow(1).createCell(0).setCellValue("2026-01-02");
            sheet.getRow(1).createCell(1).setCellValue("12.50");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] duplicateZip(String entryName, String first, String second) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
            zip.putArchiveEntry(new ZipArchiveEntry(entryName));
            zip.write(first.getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
            zip.putArchiveEntry(new ZipArchiveEntry(entryName));
            zip.write(second.getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
            zip.finish();
            return output.toByteArray();
        }
    }
}
