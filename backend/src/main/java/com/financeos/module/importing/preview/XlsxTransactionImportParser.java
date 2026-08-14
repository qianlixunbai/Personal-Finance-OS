package com.financeos.module.importing.preview;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class XlsxTransactionImportParser implements TransactionImportParser {
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_COLUMNS = 32;
    private static final long MAX_ARCHIVE_ENTRIES = 128;
    private static final long MAX_ENTRY_SIZE = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_EXPANDED_SIZE = 50L * 1024 * 1024;
    private static final long MAX_XML_NODES = 1_000_000;
    private static final int MAX_XML_ATTRIBUTES = 64;
    private static final int MAX_XML_DEPTH = 64;
    private static final long PARSE_TIMEOUT_NANOS = 10_000_000_000L;

    @Override
    public ParsedImportFile parse(InputStream input) {
        long deadlineNanos = System.nanoTime() + PARSE_TIMEOUT_NANOS;
        byte[] bytes = readBounded(input, deadlineNanos);
        inspectArchive(bytes, deadlineNanos);
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_ENTRY_SIZE);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.isDate1904() || workbook.getNumberOfSheets() != 1 || workbook.isSheetHidden(0) || workbook.isSheetVeryHidden(0)) {
                throw invalid("XLSX must contain exactly one visible worksheet");
            }
            XSSFSheet sheet = workbook.getSheetAt(0);
            if (sheet.getNumMergedRegions() != 0) {
                throw invalid("XLSX merged cells are not supported");
            }
            XSSFRow headerRow = sheet.getRow(0);
            if (headerRow == null || headerRow.getLastCellNum() <= 0) {
                throw invalid("XLSX must contain a header row");
            }
            List<String> headers = readHeaders(headerRow);
            List<SourceImportRow> rows = new ArrayList<>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                XSSFRow row = sheet.getRow(rowIndex);
                if (row != null && row.getZeroHeight()) {
                    throw invalid("XLSX hidden rows are not supported");
                }
                List<String> values = readRow(row, headers.size());
                if (values.stream().allMatch(String::isEmpty)) {
                    continue;
                }
                if (rows.size() == MAX_ROWS) {
                    throw new ImportPreviewException(413, "Import file exceeds the maximum row count");
                }
                rows.add(new SourceImportRow(rowIndex + 1, values));
            }
            if (rows.isEmpty()) {
                throw invalid("XLSX must contain at least one data row");
            }
            return new ParsedImportFile(headers, rows);
        } catch (ImportPreviewException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            checkDeadline(deadlineNanos);
            throw invalid("Invalid or unsafe XLSX file");
        }
    }

    private byte[] readBounded(InputStream input, long deadlineNanos) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkDeadline(deadlineNanos);
                output.write(buffer, 0, read);
                if (output.size() > MAX_FILE_SIZE) throw new ImportPreviewException(413, "Import file exceeds the maximum size");
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0) throw invalid("Import file must not be empty");
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw invalid("XLSX must be a ZIP archive");
            return bytes;
        } catch (IOException exception) {
            throw invalid("Import file cannot be read");
        }
    }

    private void inspectArchive(byte[] bytes, long deadlineNanos) {
        long totalExpanded = 0;
        int entries = 0;
        Set<String> entryNames = new HashSet<>();
        byte[] buffer = new byte[8192];
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                checkDeadline(deadlineNanos);
                if (++entries > MAX_ARCHIVE_ENTRIES || unsafeEntryName(entry.getName()) || !entryNames.add(entry.getName())) {
                    throw invalid("Unsafe XLSX archive");
                }
                ByteArrayOutputStream xml = entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels")
                        ? new ByteArrayOutputStream() : null;
                long entryExpanded = 0;
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    checkDeadline(deadlineNanos);
                    entryExpanded += read;
                    totalExpanded += read;
                    if (entryExpanded > MAX_ENTRY_SIZE || totalExpanded > MAX_TOTAL_EXPANDED_SIZE) {
                        throw invalid("Unsafe XLSX archive");
                    }
                    if (entry.getName().endsWith("sharedStrings.xml") && entryExpanded > 10L * 1024 * 1024) {
                        throw invalid("Unsafe XLSX archive");
                    }
                    if (xml != null) xml.write(buffer, 0, read);
                }
                long compressedSize = entry.getCompressedSize();
                if (compressedSize < 0 || entryExpanded > compressedSize * 100L) {
                    throw invalid("Unsafe XLSX archive");
                }
                if (xml != null) inspectXml(entry.getName(), xml.toByteArray(), deadlineNanos);
            }
        } catch (IOException exception) {
            throw invalid("Invalid XLSX archive");
        }
    }

    private boolean unsafeEntryName(String name) {
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || normalized.contains("../") || normalized.equals("..")
                || normalized.contains("vbaProject") || normalized.contains("embeddings/")
                || normalized.contains("activeX") || normalized.contains("oleObject");
    }

    private void inspectXml(String name, byte[] xml, long deadlineNanos) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setProperty(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setProperty(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External XML resolution is prohibited");
        });
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            long nodes = 0;
            int depth = 0;
            while (reader.hasNext()) {
                checkDeadline(deadlineNanos);
                int event = reader.next();
                if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) throw invalid("Unsafe XLSX content");
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (++nodes > MAX_XML_NODES || reader.getAttributeCount() > MAX_XML_ATTRIBUTES || ++depth > MAX_XML_DEPTH) {
                        throw invalid("XLSX XML resource limit exceeded");
                    }
                    if ("http://www.w3.org/2001/XInclude".equals(reader.getNamespaceURI())
                            || "include".equals(reader.getLocalName()) && "xi".equals(reader.getPrefix())
                            || name.endsWith(".rels") && hasExternalRelationship(reader)) {
                        throw invalid("Unsafe XLSX content");
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    depth--;
                }
            }
            reader.close();
        } catch (ImportPreviewException exception) {
            throw exception;
        } catch (XMLStreamException exception) {
            throw invalid("Unsafe XLSX content");
        }
    }

    private boolean hasExternalRelationship(XMLStreamReader reader) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if ("TargetMode".equals(reader.getAttributeLocalName(index)) && "external".equalsIgnoreCase(reader.getAttributeValue(index))) return true;
        }
        return false;
    }

    private void setProperty(XMLInputFactory factory, String property, Object value) {
        try { factory.setProperty(property, value); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("Required XML security property is unavailable: " + property, exception); }
    }

    private void checkDeadline(long deadlineNanos) {
        if (System.nanoTime() > deadlineNanos) throw new ImportPreviewException(408, "Import parse timeout");
    }

    private List<String> readHeaders(XSSFRow row) {
        if (row.getLastCellNum() > MAX_COLUMNS) throw invalid("XLSX header has too many columns");
        Set<String> seen = new HashSet<>();
        List<String> headers = new ArrayList<>();
        for (int column = 0; column < row.getLastCellNum(); column++) {
            if (row.getSheet().isColumnHidden(column)) throw invalid("XLSX hidden columns are not supported");
            String value = readCell(row.getCell(column));
            String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
            if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 128 || !seen.add(normalized)) {
                throw invalid("XLSX header is empty, too long, or duplicated");
            }
            headers.add(normalized);
        }
        return headers;
    }

    private List<String> readRow(XSSFRow row, int columns) {
        List<String> values = new ArrayList<>(columns);
        for (int column = 0; column < columns; column++) {
            values.add(readCell(row == null ? null : row.getCell(column)));
        }
        if (row != null && row.getLastCellNum() > columns) throw invalid("XLSX row column count does not match header");
        if (values.stream().anyMatch(value -> value.codePointCount(0, value.length()) > 4_096)) throw invalid("XLSX value exceeds contract limits");
        return values;
    }

    private String readCell(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() == CellType.FORMULA || cell.getCellType() == CellType.BOOLEAN || cell.getCellType() == CellType.ERROR) {
            throw invalid("XLSX contains an unsupported cell type");
        }
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        XSSFCell xssfCell = (XSSFCell) cell;
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime value = cell.getLocalDateTimeCellValue();
            String format = cell.getCellStyle().getDataFormatString().toLowerCase(Locale.ROOT);
            if ((format.contains("h") || format.contains("s")) && !format.contains("d") && !format.contains("y")) {
                return value.toLocalTime().toString();
            }
            if (!value.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                throw invalid("XLSX date-time cells with a time component are not supported");
            }
            return value.toLocalDate().toString();
        }
        String raw = xssfCell.getRawValue();
        if (raw == null || raw.isBlank()) throw invalid("XLSX numeric cell is invalid");
        return raw;
    }

    private ImportPreviewException invalid(String message) {
        return new ImportPreviewException(400, message);
    }
}
