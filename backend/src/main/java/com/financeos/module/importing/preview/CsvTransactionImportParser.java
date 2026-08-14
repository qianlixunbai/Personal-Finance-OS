package com.financeos.module.importing.preview;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CsvTransactionImportParser implements TransactionImportParser {
    static final int MAX_FILE_SIZE = 5 * 1024 * 1024;
    static final int MAX_ROWS = 10_000;
    static final int MAX_COLUMNS = 32;
    static final int MAX_HEADER_CODE_POINTS = 128;
    static final int MAX_VALUE_CODE_POINTS = 4_096;
    private static final long PARSE_TIMEOUT_NANOS = 10_000_000_000L;

    @Override
    public ParsedImportFile parse(InputStream input) {
        long deadlineNanos = System.nanoTime() + PARSE_TIMEOUT_NANOS;
        String content = decodeStrictUtf8(readBounded(input, deadlineNanos));
        checkDeadline(deadlineNanos);
        rejectUnsafeLineEndings(content);
        try (Reader reader = new java.io.StringReader(stripBom(content));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setQuote('"')
                     .setRecordSeparator("\n")
                     .setIgnoreEmptyLines(false)
                     .get()
                     .parse(reader)) {
            List<CSVRecord> records = parser.getRecords();
            List<Integer> recordStartLines = recordStartLines(stripBom(content));
            if (records.isEmpty()) {
                throw invalid("Import CSV must contain a header");
            }
            CSVRecord header = records.getFirst();
            List<String> headers = normalizeHeaders(Arrays.asList(header.values()));
            List<SourceImportRow> rows = new ArrayList<>();
            for (int index = 1; index < records.size(); index++) {
                checkDeadline(deadlineNanos);
                CSVRecord record = records.get(index);
                if (record.size() == 1 && record.get(0).isEmpty()) {
                    continue;
                }
                if (record.size() != headers.size()) {
                    throw invalid("CSV row column count does not match header");
                }
                List<String> values = Arrays.asList(record.values());
                validateValues(values);
                if (values.stream().allMatch(String::isEmpty)) {
                    continue;
                }
                if (rows.size() == MAX_ROWS) {
                    throw new ImportPreviewException(413, "Import file exceeds the maximum row count");
                }
                rows.add(new SourceImportRow(recordStartLines.get(index), values));
            }
            if (rows.isEmpty()) {
                throw invalid("Import CSV must contain at least one data row");
            }
            return new ParsedImportFile(headers, rows);
        } catch (IOException | UncheckedIOException | IllegalArgumentException exception) {
            throw invalid("Invalid CSV file");
        }
    }

    private byte[] readBounded(InputStream input, long deadlineNanos) {
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkDeadline(deadlineNanos);
                output.write(buffer, 0, read);
                if (output.size() > MAX_FILE_SIZE) throw new ImportPreviewException(413, "Import file exceeds the maximum size");
            }
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0) {
                throw invalid("Import file must not be empty");
            }
            return bytes;
        } catch (IOException exception) {
            throw invalid("Import file cannot be read");
        }
    }

    private String decodeStrictUtf8(byte[] bytes) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (decoded.indexOf('\0') >= 0) {
                throw invalid("CSV must not contain NUL");
            }
            return decoded;
        } catch (CharacterCodingException exception) {
            throw invalid("CSV must be UTF-8");
        }
    }

    private void rejectUnsafeLineEndings(String content) {
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < content.length() && content.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == '\r' && (i + 1 == content.length() || content.charAt(i + 1) != '\n') && !quoted) {
                throw invalid("CSV must use LF or CRLF line endings");
            }
        }
    }

    private List<String> normalizeHeaders(List<String> rawHeaders) {
        if (rawHeaders.isEmpty() || rawHeaders.size() > MAX_COLUMNS) {
            throw invalid("CSV header has an invalid column count");
        }
        Set<String> seen = new HashSet<>();
        List<String> headers = new ArrayList<>(rawHeaders.size());
        for (String raw : rawHeaders) {
            String normalized = normalizeKey(raw);
            if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > MAX_HEADER_CODE_POINTS || !seen.add(normalized)) {
                throw invalid("CSV header is empty, too long, or duplicated");
            }
            headers.add(normalized);
        }
        return headers;
    }

    private void validateValues(List<String> values) {
        if (values.size() > MAX_COLUMNS || values.stream().anyMatch(value -> value.codePointCount(0, value.length()) > MAX_VALUE_CODE_POINTS)) {
            throw invalid("CSV value exceeds contract limits");
        }
    }

    private String normalizeKey(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    }

    private String stripBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    private List<Integer> recordStartLines(String content) {
        List<Integer> lines = new ArrayList<>();
        lines.add(1);
        boolean quoted = false;
        int line = 1;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < content.length() && content.charAt(index + 1) == '"') index++;
                else quoted = !quoted;
            } else if (current == '\n' && !quoted && index + 1 < content.length()) {
                lines.add(++line);
            } else if (current == '\n') {
                line++;
            }
        }
        return lines;
    }

    private ImportPreviewException invalid(String message) {
        return new ImportPreviewException(400, message);
    }

    private void checkDeadline(long deadlineNanos) {
        if (System.nanoTime() > deadlineNanos) throw new ImportPreviewException(408, "Import parse timeout");
    }
}
