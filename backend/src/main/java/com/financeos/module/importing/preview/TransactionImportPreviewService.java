package com.financeos.module.importing.preview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.mapper.AccountMapper;
import com.financeos.module.category.entity.Category;
import com.financeos.module.category.mapper.CategoryMapper;
import com.financeos.module.importing.dto.TransactionImportFormat;
import com.financeos.module.importing.dto.TransactionImportMapping;
import com.financeos.module.importing.dto.TransactionImportPreviewRequest;
import com.financeos.module.importing.dto.TransactionImportPreviewResponse;
import com.financeos.module.importing.dto.TransactionImportPreviewRow;
import com.financeos.module.importing.dto.TransactionImportPreviewSummary;
import com.financeos.module.importing.dto.TransactionImportValidationMessage;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.service.TransactionImportSessionService;
import com.financeos.module.importing.service.TransactionImportPreviewTokenService;
import com.financeos.module.importing.storage.StoredImportFile;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import com.financeos.module.ledger.mapper.TransactionMapper;
import com.financeos.module.ledger.dto.TransactionDuplicateProbe;
import com.financeos.module.ledger.entity.Transaction;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class TransactionImportPreviewService {
    private static final Set<String> REQUIRED = Set.of("date", "type", "amount", "account", "category");
    private static final Set<String> OPTIONAL = Set.of("time", "description", "currency");
    private final TransactionImportSessionService sessionService;
    private final TransactionImportSessionMapper sessionMapper;
    private final TemporaryImportFileStorage storage;
    private final TemporaryImportFileStorage planStorage;
    private final AccountMapper accountMapper;
    private final CategoryMapper categoryMapper;
    private final TransactionMapper transactionMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionImportPreviewTokenService previewTokenService;

    @Autowired
    public TransactionImportPreviewService(TransactionImportSessionService sessionService,
                                           TransactionImportSessionMapper sessionMapper,
                                           TemporaryImportFileStorage storage, AccountMapper accountMapper,
                                           CategoryMapper categoryMapper, TransactionMapper transactionMapper,
                                           @Qualifier("transactionImportPreviewPlanStorage") TemporaryImportFileStorage planStorage,
                                           ObjectMapper objectMapper, @Qualifier("businessClock") Clock clock,
                                           TransactionImportPreviewTokenService previewTokenService) {
        this.sessionService = sessionService;
        this.sessionMapper = sessionMapper;
        this.storage = storage;
        this.planStorage = planStorage;
        this.accountMapper = accountMapper;
        this.categoryMapper = categoryMapper;
        this.transactionMapper = transactionMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.previewTokenService = previewTokenService;
    }

    /** Compatibility constructor for focused unit tests that do not bootstrap Spring. */
    public TransactionImportPreviewService(TransactionImportSessionService sessionService,
                                           TransactionImportSessionMapper sessionMapper,
                                           TemporaryImportFileStorage storage, AccountMapper accountMapper,
                                           CategoryMapper categoryMapper, TransactionMapper transactionMapper,
                                           TemporaryImportFileStorage planStorage, ObjectMapper objectMapper, Clock clock) {
        this(sessionService, sessionMapper, storage, accountMapper, categoryMapper, transactionMapper, planStorage,
                objectMapper, clock, new TransactionImportPreviewTokenService(objectMapper, "transaction-import-preview-test-token-secret"));
    }

    public TransactionImportPreviewResponse create(Long userId, MultipartFile file, TransactionImportPreviewRequest request) {
        if (file == null || file.isEmpty()) throw new ImportPreviewException(400, "Import file must not be empty");
        TransactionImportFormat format = resolveFormat(request.format(), file.getOriginalFilename(), file.getContentType());
        StoredImportFile stored;
        try {
            stored = storage.save(file.getInputStream(), file.getOriginalFilename());
        } catch (IOException exception) {
            throw new ImportPreviewException(400, "Import file cannot be read");
        }
        try {
            ParsedImportFile parsed = parse(format, stored.reference());
            TransactionImportSession draft = new TransactionImportSession();
            draft.setOriginalFileName(com.financeos.module.importing.storage.ImportFileMetadata.sanitizeOriginalFilename(file.getOriginalFilename()));
            draft.setContentType(file.getContentType());
            draft.setFileSize(stored.fileSize());
            draft.setFileDigest(stored.fileDigest());
            draft.setTemporaryStorageReference(stored.reference());
            draft.setOptionsDigest(digest("phase-3c|" + format.name()));
            TransactionImportSession session = sessionService.createMappingRequiredSession(userId, draft);
            if (!isComplete(parsed.headers(), request.mapping())) return mappingRequired(session, parsed.headers());
            return materialize(userId, session, parsed, request.mapping());
        } catch (RuntimeException exception) {
            storage.delete(stored.reference());
            throw exception;
        }
    }

    public TransactionImportPreviewResponse updateMapping(Long userId, java.util.UUID sessionId, TransactionImportMapping mapping) {
        TransactionImportSession session = requireUsableSession(userId, sessionId);
        ParsedImportFile parsed = parse(resolveStoredFormat(session), session.getTemporaryStorageReference());
        if (!isComplete(parsed.headers(), mapping)) return invalidatePreview(userId, session, parsed.headers());
        return materialize(userId, session, parsed, mapping);
    }

    public List<TransactionImportPreviewRow> getRows(Long userId, java.util.UUID sessionId, int page, int size) {
        TransactionImportSession session = requireUsableSession(userId, sessionId);
        if (session.getPlanStorageReference() == null) throw new BusinessException(409, "Import mapping is required");
        TransactionImportPreviewPlan plan = readPlan(session.getPlanStorageReference());
        int safeSize = Math.min(Math.max(size, 1), 500);
        int from = Math.min((Math.max(page, 1) - 1) * safeSize, plan.rows().size());
        return plan.rows().subList(from, Math.min(from + safeSize, plan.rows().size()));
    }

    public void cancel(Long userId, java.util.UUID sessionId) {
        TransactionImportSession session = requireUsableSession(userId, sessionId);
        Instant now = clock.instant();
        if (sessionMapper.cancel(sessionId, userId, now) != 1) {
            throw new BusinessException(409, "Import session is not cancellable");
        }
        storage.delete(session.getTemporaryStorageReference());
        if (session.getPlanStorageReference() != null) planStorage.delete(session.getPlanStorageReference());
        sessionMapper.clearCleanupReferences(sessionId, userId);
    }

    private TransactionImportSession requireUsableSession(Long userId, java.util.UUID sessionId) {
        try {
            return sessionService.requireUsableSession(userId, sessionId);
        } catch (BusinessException exception) {
            TransactionImportSession session = sessionMapper.findByIdAndUserId(sessionId, userId);
            if (session != null && "EXPIRED".equals(session.getStatus())) {
                if (session.getTemporaryStorageReference() != null) storage.delete(session.getTemporaryStorageReference());
                if (session.getPlanStorageReference() != null) planStorage.delete(session.getPlanStorageReference());
                sessionMapper.clearCleanupReferences(sessionId, userId);
            }
            throw exception;
        }
    }

    private TransactionImportPreviewResponse invalidatePreview(Long userId, TransactionImportSession session, List<String> headers) {
        if (!"MAPPING_REQUIRED".equals(session.getStatus()) || session.getPlanStorageReference() != null) {
            if (sessionMapper.invalidatePreview(session.getId(), userId, clock.instant()) != 1) {
                throw new BusinessException(409, "Import session is no longer usable");
            }
            if (session.getPlanStorageReference() != null) planStorage.delete(session.getPlanStorageReference());
            session = sessionMapper.findByIdAndUserId(session.getId(), userId);
        }
        return mappingRequired(session, headers);
    }

    private TransactionImportPreviewResponse materialize(Long userId, TransactionImportSession session, ParsedImportFile parsed,
                                                          TransactionImportMapping mapping) {
        // The session revision changes as this preview is committed.  Bind every warning to
        // that resulting revision, so an acknowledgement cannot be reused for another plan.
        List<TransactionImportPreviewRow> rows = bindWarningIdentities(validate(userId, parsed, mapping), session,
                session.getRevision() + 1);
        TransactionImportPreviewSummary summary = summarize(rows);
        String mappingDigest = digest(canonicalMapping(mapping));
        String rowsDigest = digest(canonicalRows(rows));
        TransactionImportPreviewPlan plan = new TransactionImportPreviewPlan(parsed.headers(), mapping, rows, summary);
        StoredImportFile storedPlan = storePlan(plan);
        int updated = sessionMapper.markPreviewReady(session.getId(), userId, mappingDigest, rowsDigest,
                storedPlan.reference(), clock.instant());
        if (updated != 1) {
            planStorage.delete(storedPlan.reference());
            throw new BusinessException(409, "Import session is no longer usable");
        }
        if (session.getPlanStorageReference() != null) planStorage.delete(session.getPlanStorageReference());
        TransactionImportSession ready = sessionMapper.findByIdAndUserId(session.getId(), userId);
        return response(ready, parsed.headers(), mapping, rows, summary);
    }

    private List<TransactionImportPreviewRow> validate(Long userId, ParsedImportFile parsed, TransactionImportMapping mapping) {
        List<TransactionImportPreviewRow> rows = new ArrayList<>();
        Map<Long, Account> accounts = accountMapper.findOwnedByIds(userId, mapping.accountMappings().values().stream().distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Account::getId, account -> account));
        Map<Long, Category> categories = categoryMapper.selectVisibleByIds(userId, mapping.categoryMappings().values().stream().distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(Category::getId, category -> category));
        Map<String, Integer> headerIndex = new HashMap<>();
        for (int index = 0; index < parsed.headers().size(); index++) headerIndex.put(parsed.headers().get(index), index);
        Map<String, List<Integer>> fingerprintRows = new HashMap<>();
        Map<Integer, String> fingerprintsByRow = new HashMap<>();
        for (SourceImportRow source : parsed.rows()) {
            Map<String, String> sourceValues = sourceValues(parsed.headers(), source.values());
            List<TransactionImportValidationMessage> errors = new ArrayList<>();
            Map<String, String> normalized = new LinkedHashMap<>();
            String type = mappedValue(sourceValues, mapping.columnMappings().get("type"), mapping.typeMappings(), errors, source.rowNumber(), "type", "INVALID_TRANSACTION_TYPE");
            String accountValue = rawValue(sourceValues, mapping.columnMappings().get("account"));
            String categoryValue = rawValue(sourceValues, mapping.columnMappings().get("category"));
            String amount = normalizeAmount(rawValue(sourceValues, mapping.columnMappings().get("amount")), type, errors, source.rowNumber());
            String date = normalizeDate(rawValue(sourceValues, mapping.columnMappings().get("date")), errors, source.rowNumber());
            String time = normalizeTime(rawValue(sourceValues, mapping.columnMappings().get("time")), errors, source.rowNumber());
            String currency = normalizeCurrency(rawValue(sourceValues, mapping.columnMappings().get("currency")), errors, source.rowNumber());
            String description = normalizeDescription(rawValue(sourceValues, mapping.columnMappings().get("description")), type, errors, source.rowNumber());
            validateAccount(accountValue, mapping.accountMappings(), accounts, errors, source.rowNumber());
            validateCategory(categoryValue, type, mapping.categoryMappings(), categories, errors, source.rowNumber());
            normalized.put("type", type == null ? "" : type);
            normalized.put("amount", amount == null ? "" : amount);
            normalized.put("date", date == null ? "" : date);
            normalized.put("time", time == null ? "" : time);
            normalized.put("transactedAt", date == null || time == null ? "" : LocalDateTime.parse(date + "T" + time).toString());
            normalized.put("currency", currency == null ? "" : currency);
            normalized.put("description", description == null ? "" : description);
            normalized.put("account", normalizeKey(accountValue));
            normalized.put("category", normalizeKey(categoryValue));
            if (errors.isEmpty()) {
                String fingerprint = digest(canonicalMap(authoritativeFingerprintValues(normalized, mapping)));
                fingerprintsByRow.put(source.rowNumber(), fingerprint);
                fingerprintRows.computeIfAbsent(fingerprint, ignored -> new ArrayList<>()).add(source.rowNumber());
            }
            rows.add(new TransactionImportPreviewRow(source.rowNumber(), sourceValues, normalized,
                    errors.isEmpty() ? "MAPPED" : "INVALID", errors, List.of(), "NONE", errors.isEmpty()));
        }
        return applyDatabaseDuplicateWarnings(userId, applyInFileDuplicateWarnings(rows, fingerprintRows, fingerprintsByRow), mapping);
    }

    private List<TransactionImportPreviewRow> applyDatabaseDuplicateWarnings(Long userId, List<TransactionImportPreviewRow> rows,
                                                                               TransactionImportMapping mapping) {
        List<TransactionDuplicateProbe> probes = rows.stream().filter(row -> row.errors().isEmpty())
                .map(row -> duplicateProbe(row, mapping)).toList();
        Map<String, List<Long>> candidateIds = probes.isEmpty() ? Map.of()
                : transactionMapper.findProbableDuplicateCandidates(userId, probes).stream()
                .collect(java.util.stream.Collectors.groupingBy(this::duplicateKey,
                        java.util.stream.Collectors.mapping(Transaction::getId, java.util.stream.Collectors.toList())));
        List<TransactionImportPreviewRow> result = new ArrayList<>(rows.size());
        for (TransactionImportPreviewRow row : rows) {
            if (!row.errors().isEmpty()) { result.add(row); continue; }
            try {
                List<Long> matches = candidateIds.getOrDefault(duplicateKey(duplicateProbe(row, mapping)), List.of());
                if (!matches.isEmpty()) {
                    List<TransactionImportValidationMessage> warnings = new ArrayList<>(row.warnings());
                    warnings.add(message("DATABASE_PROBABLE", "row", row.rowNumber(), "duplicate",
                            "This row is a probable duplicate of an existing transaction", matches.toString()));
                    result.add(new TransactionImportPreviewRow(row.rowNumber(), row.sourceValues(), row.normalizedValues(),
                            row.mappingStatus(), row.errors(), warnings, "DATABASE_PROBABLE", true));
                } else result.add(row);
            } catch (RuntimeException exception) { throw exception; }
        }
        return result;
    }

    private TransactionDuplicateProbe duplicateProbe(TransactionImportPreviewRow row, TransactionImportMapping mapping) {
        return new TransactionDuplicateProbe(mapping.accountMappings().get(row.normalizedValues().get("account")),
                mapping.categoryMappings().get(row.normalizedValues().get("category")), row.normalizedValues().get("type"),
                new BigDecimal(row.normalizedValues().get("amount")), LocalDateTime.parse(row.normalizedValues().get("transactedAt")),
                row.normalizedValues().get("description"));
    }
    private String duplicateKey(TransactionDuplicateProbe probe) {
        return probe.accountId() + "|" + probe.categoryId() + "|" + probe.type() + "|" + probe.amount().toPlainString()
                + "|" + probe.transactedAt() + "|" + probe.description();
    }
    private String duplicateKey(Transaction transaction) {
        return duplicateKey(new TransactionDuplicateProbe(transaction.getAccountId(), transaction.getCategoryId(), transaction.getType(),
                transaction.getAmount(), transaction.getTransactedAt(), transaction.getDescription()));
    }

    private List<TransactionImportPreviewRow> applyInFileDuplicateWarnings(List<TransactionImportPreviewRow> rows,
                                                                             Map<String, List<Integer>> fingerprintRows,
                                                                             Map<Integer, String> fingerprintsByRow) {
        List<TransactionImportPreviewRow> result = new ArrayList<>(rows.size());
        for (TransactionImportPreviewRow row : rows) {
            List<Integer> matching = fingerprintRows.get(fingerprintsByRow.get(row.rowNumber()));
            if (row.errors().isEmpty() && matching.size() > 1) {
                TransactionImportValidationMessage warning = message("IN_FILE_PROBABLE", "row", row.rowNumber(), "duplicate",
                        "This row is a probable duplicate within the uploaded file", matching.toString());
                result.add(new TransactionImportPreviewRow(row.rowNumber(), row.sourceValues(), row.normalizedValues(),
                        row.mappingStatus(), row.errors(), List.of(warning), "IN_FILE_PROBABLE", true));
            } else result.add(row);
        }
        return result;
    }

    private Map<String, String> authoritativeFingerprintValues(Map<String, String> normalized, TransactionImportMapping mapping) {
        Map<String, String> fingerprint = new LinkedHashMap<>(normalized);
        fingerprint.put("account", String.valueOf(mapping.accountMappings().get(normalized.get("account"))));
        fingerprint.put("category", String.valueOf(mapping.categoryMappings().get(normalized.get("category"))));
        return fingerprint;
    }

    private String mappedValue(Map<String, String> source, String column, Map<String, String> mappings,
                               List<TransactionImportValidationMessage> errors, int rowNumber, String field, String code) {
        String raw = rawValue(source, column);
        String value = mappings.get(normalizeKey(raw));
        if (value == null || !Set.of("INCOME", "EXPENSE", "ADJUSTMENT").contains(value)) {
            errors.add(message(code, "row", rowNumber, field, "A supported transaction type mapping is required"));
            return null;
        }
        return value;
    }

    private String normalizeAmount(String raw, String type, List<TransactionImportValidationMessage> errors, int rowNumber) {
        String value = raw == null ? "" : raw.strip();
        if (!value.matches("-?(?:0|[1-9]\\d{0,15})(?:\\.\\d{1,2})?")) return amountError(errors, rowNumber);
        try {
            BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.abs().compareTo(new BigDecimal("9999999999999999.99")) > 0
                    || ("INCOME".equals(type) || "EXPENSE".equals(type)) && amount.signum() <= 0
                    || "ADJUSTMENT".equals(type) && amount.signum() == 0) return amountError(errors, rowNumber);
            return amount.toPlainString();
        } catch (ArithmeticException exception) { return amountError(errors, rowNumber); }
    }

    private String amountError(List<TransactionImportValidationMessage> errors, int rowNumber) {
        errors.add(message("INVALID_AMOUNT", "row", rowNumber, "amount", "Amount must be a supported fixed-scale decimal"));
        return null;
    }

    private String normalizeDate(String raw, List<TransactionImportValidationMessage> errors, int rowNumber) {
        try {
            if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("invalid", String.valueOf(raw), 0);
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE).toString();
        } catch (DateTimeParseException exception) {
            errors.add(message("INVALID_DATE", "row", rowNumber, "date", "Date must use YYYY-MM-DD")); return null;
        }
    }

    private String normalizeTime(String raw, List<TransactionImportValidationMessage> errors, int rowNumber) {
        if (raw == null || raw.isEmpty()) return "00:00:00";
        try {
            if (!raw.matches("\\d{2}:\\d{2}(?::\\d{2})?")) throw new DateTimeParseException("invalid", raw, 0);
            return LocalTime.parse(raw.length() == 5 ? raw + ":00" : raw).toString();
        } catch (DateTimeParseException exception) {
            errors.add(message("INVALID_DATE", "row", rowNumber, "time", "Time must use HH:mm or HH:mm:ss")); return null;
        }
    }

    private String normalizeCurrency(String raw, List<TransactionImportValidationMessage> errors, int rowNumber) {
        String currency = raw == null || raw.isBlank() ? "CNY" : raw.trim();
        if (!"CNY".equals(currency)) errors.add(message("UNSUPPORTED_CURRENCY", "row", rowNumber, "currency", "Only CNY is supported"));
        return currency;
    }

    private String normalizeDescription(String raw, String type, List<TransactionImportValidationMessage> errors, int rowNumber) {
        String value = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        if (value.codePointCount(0, value.length()) > 500 || value.codePoints().anyMatch(code -> (code < 32 && code != '\n') || code == 127)
                || "ADJUSTMENT".equals(type) && value.isBlank()) {
            errors.add(message("MISSING_REQUIRED_FIELD", "row", rowNumber, "description", "Description is invalid for this transaction type"));
        }
        return value;
    }

    private void validateAccount(String raw, Map<String, Long> mappings, Map<Long, Account> accounts, List<TransactionImportValidationMessage> errors, int rowNumber) {
        Long id = mappings.get(normalizeKey(raw));
        Account account = id == null ? null : accounts.get(id);
        if (id == null) errors.add(message("ACCOUNT_NOT_MAPPED", "row", rowNumber, "account", "Account mapping is required"));
        else if (account == null) errors.add(message("ACCOUNT_NOT_FOUND", "row", rowNumber, "account", "Account is not available"));
        else if (!"ACTIVE".equals(account.getStatus())) errors.add(message("ACCOUNT_INACTIVE", "row", rowNumber, "account", "Account is inactive"));
        else if (!"CNY".equals(account.getCurrency())) errors.add(message("UNSUPPORTED_CURRENCY", "row", rowNumber, "account", "Account currency must be CNY"));
    }

    private void validateCategory(String raw, String type, Map<String, Long> mappings, Map<Long, Category> categories, List<TransactionImportValidationMessage> errors, int rowNumber) {
        Long id = mappings.get(normalizeKey(raw));
        Category category = id == null ? null : categories.get(id);
        boolean visible = category != null;
        if (id == null) errors.add(message("CATEGORY_NOT_MAPPED", "row", rowNumber, "category", "Category mapping is required"));
        else if (!visible) errors.add(message("CATEGORY_NOT_MAPPED", "row", rowNumber, "category", "Category is not available"));
        else if (("INCOME".equals(type) || "EXPENSE".equals(type)) && !type.equals(category.getType()))
            errors.add(message("CATEGORY_TYPE_MISMATCH", "row", rowNumber, "category", "Category is incompatible with transaction type"));
    }

    private boolean isComplete(List<String> headers, TransactionImportMapping mapping) {
        if (mapping == null) return false;
        Set<String> used = new java.util.HashSet<>();
        for (String target : REQUIRED) {
            String header = mapping.columnMappings().get(target);
            if (header == null || !headers.contains(header) || !used.add(header)) return false;
        }
        for (String target : OPTIONAL) {
            String header = mapping.columnMappings().get(target);
            if (header != null && (!headers.contains(header) || !used.add(header))) return false;
        }
        return headers.stream().allMatch(header -> used.contains(header) || "IGNORE".equals(mapping.columnMappings().get(header)));
    }

    private TransactionImportPreviewResponse mappingRequired(TransactionImportSession session, List<String> headers) {
        return new TransactionImportPreviewResponse(session.getId(), null, session.getStatus(), session.getRevision(), headers,
                null, List.of(), new TransactionImportPreviewSummary(0, 0, 0, 0, 0, 0), session.getFileDigest(), null,
                session.getOptionsDigest(), null, session.getExpiresAt(), false, null);
    }

    private TransactionImportPreviewResponse response(TransactionImportSession session, List<String> headers, TransactionImportMapping mapping,
                                                       List<TransactionImportPreviewRow> rows, TransactionImportPreviewSummary summary) {
        return new TransactionImportPreviewResponse(session.getId(), session.getPreallocatedBatchId(), session.getStatus(), session.getRevision(),
                headers, mapping, rows.subList(0, Math.min(100, rows.size())), summary, session.getFileDigest(), session.getMappingDigest(),
                session.getOptionsDigest(), session.getNormalizedRowsDigest(), session.getExpiresAt(), summary.errorRows() == 0,
                previewTokenService.issue(session, rows.stream().flatMap(row -> row.warnings().stream()).map(TransactionImportValidationMessage::id).toList()));
    }

    private ParsedImportFile parse(TransactionImportFormat format, String reference) {
        try (var content = storage.open(reference)) {
            return (format == TransactionImportFormat.CSV ? new CsvTransactionImportParser() : new XlsxTransactionImportParser()).parse(content);
        } catch (IOException exception) { throw new ImportPreviewException(400, "Import file cannot be read"); }
    }

    private TransactionImportFormat resolveStoredFormat(TransactionImportSession session) {
        return session.getOriginalFileName().toLowerCase(Locale.ROOT).endsWith(".csv") ? TransactionImportFormat.CSV : TransactionImportFormat.XLSX;
    }

    private TransactionImportFormat resolveFormat(TransactionImportFormat requested, String filename, String contentType) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        TransactionImportFormat detected = name.endsWith(".csv") ? TransactionImportFormat.CSV : name.endsWith(".xlsx") ? TransactionImportFormat.XLSX : null;
        if (detected == null || requested != TransactionImportFormat.AUTO && requested != detected) throw new ImportPreviewException(415, "Unsupported import format");
        if (contentType != null && !contentType.equals("application/octet-stream")
                && detected == TransactionImportFormat.CSV && !Set.of("text/csv", "application/csv").contains(contentType)
                || contentType != null && !contentType.equals("application/octet-stream")
                && detected == TransactionImportFormat.XLSX && !contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
            throw new ImportPreviewException(415, "Import content type does not match file format");
        }
        return detected;
    }

    private StoredImportFile storePlan(TransactionImportPreviewPlan plan) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(plan);
            return planStorage.save(new ByteArrayInputStream(payload), "preview-plan.json");
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize import preview plan", exception); }
    }

    public TransactionImportPreviewPlan loadFrozenPlan(String reference) {
        return readPlan(reference);
    }

    private TransactionImportPreviewPlan readPlan(String reference) {
        try (var input = planStorage.open(reference)) { return objectMapper.readValue(input, TransactionImportPreviewPlan.class); }
        catch (IOException exception) { throw new BusinessException(409, "Import preview plan is unavailable"); }
    }

    private TransactionImportPreviewSummary summarize(List<TransactionImportPreviewRow> rows) {
        int errors = (int) rows.stream().filter(row -> !row.errors().isEmpty()).count();
        int warnings = (int) rows.stream().filter(row -> row.errors().isEmpty() && !row.warnings().isEmpty()).count();
        int valid = rows.size() - errors - warnings;
        return new TransactionImportPreviewSummary(rows.size(), valid, warnings, errors, valid + warnings, warnings);
    }

    private Map<String, String> sourceValues(List<String> headers, List<String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) result.put(headers.get(index), values.get(index));
        return result;
    }

    private String rawValue(Map<String, String> values, String header) { return header == null ? null : values.get(header); }
    private String normalizeKey(String value) { return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).trim(); }
    private List<TransactionImportPreviewRow> bindWarningIdentities(List<TransactionImportPreviewRow> rows,
                                                                      TransactionImportSession session, int revision) {
        return rows.stream().map(row -> new TransactionImportPreviewRow(row.rowNumber(), row.sourceValues(), row.normalizedValues(),
                row.mappingStatus(), row.errors(), row.warnings().stream()
                .map(warning -> new TransactionImportValidationMessage(digest(session.getId() + "|" + revision + "|"
                        + canonicalMap(row.normalizedValues()) + "|" + warning.id()), warning.code(), warning.scope(),
                        warning.field(), warning.rowNumber(), warning.message(), warning.retryable())).toList(),
                row.duplicateStatus(), row.importable())).toList();
    }
    private TransactionImportValidationMessage message(String code, String scope, int row, String field, String text) {
        return message(code, scope, row, field, text, "");
    }
    private TransactionImportValidationMessage message(String code, String scope, int row, String field, String text, String evidence) {
        return new TransactionImportValidationMessage(digest(code + "|" + row + "|" + field + "|" + evidence), code, scope, field, row, text, false);
    }
    private String canonicalMapping(TransactionImportMapping mapping) { return canonicalMap(new TreeMap<>(Map.of("columns", canonicalMap(mapping.columnMappings()), "types", canonicalMap(mapping.typeMappings()), "accounts", canonicalMap(mapping.accountMappings()), "categories", canonicalMap(mapping.categoryMappings())))); }
    // Duplicate evidence is dynamic and is validated separately at Confirm.  The immutable
    // import identity must describe canonical input, not the set of transactions visible
    // when a preview happened to be generated.
    private String canonicalRows(List<TransactionImportPreviewRow> rows) { return rows.stream().sorted(Comparator.comparingInt(TransactionImportPreviewRow::rowNumber)).map(row -> atom(String.valueOf(row.rowNumber())) + atom(canonicalMap(row.normalizedValues()))).reduce("", (left, right) -> left + right); }
    private String canonicalMap(Map<?, ?> map) { return map.entrySet().stream().sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey()))).map(entry -> atom(String.valueOf(entry.getKey())) + atom(String.valueOf(entry.getValue()))).reduce("", (left, right) -> left + right); }
    private String atom(String value) { return value.length() + ":" + value; }
    private String digest(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }

}
