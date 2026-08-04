package com.financeos.module.investment.read.cursor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned, URL-safe, opaque cursors for stable investment read pagination. */
public final class CursorCodec {
    private static final int VERSION = 1;
    private static final int MAX_CURSOR_LENGTH = 2048;
    private static final ObjectMapper JSON = new ObjectMapper();

    private CursorCodec() {
    }

    public static String encodePosition(PositionCursor cursor, String fingerprint) {
        requirePositive(cursor.instrumentId());
        requirePositive(cursor.accountId());
        requirePositive(cursor.positionId());
        return encode(CursorKind.POSITION_LIST, fingerprint, Map.of(
                "instrumentId", cursor.instrumentId(), "accountId", cursor.accountId(), "positionId", cursor.positionId()));
    }

    public static String encodeLogicalTransaction(LogicalTransactionCursor cursor, String fingerprint) {
        if (cursor.effectiveTradeTime() == null) {
            throw invalid();
        }
        requirePositive(cursor.logicalTransactionId());
        return encode(CursorKind.LOGICAL_TRANSACTION_LIST, fingerprint, Map.of(
                "effectiveTradeTime", cursor.effectiveTradeTime().toString(),
                "logicalTransactionId", cursor.logicalTransactionId()));
    }

    public static PositionCursor decodePosition(String raw, String expectedFingerprint) {
        Map<String, Object> payload = decode(raw, CursorKind.POSITION_LIST, expectedFingerprint);
        return new PositionCursor(longValue(payload, "instrumentId"), longValue(payload, "accountId"), longValue(payload, "positionId"));
    }

    public static LogicalTransactionCursor decodeLogicalTransaction(String raw, String expectedFingerprint) {
        Map<String, Object> payload = decode(raw, CursorKind.LOGICAL_TRANSACTION_LIST, expectedFingerprint);
        try {
            return new LogicalTransactionCursor(Instant.parse(stringValue(payload, "effectiveTradeTime")),
                    longValue(payload, "logicalTransactionId"));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static String encode(CursorKind kind, String fingerprint, Map<String, Object> sortKey) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", VERSION);
        payload.put("cursorKind", kind.name());
        payload.put("filterFingerprint", fingerprint);
        payload.putAll(sortKey);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encode read cursor", exception);
        }
    }

    private static Map<String, Object> decode(String raw, CursorKind expectedKind, String expectedFingerprint) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_CURSOR_LENGTH || expectedFingerprint == null) {
            throw invalid();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            if (bytes.length == 0 || bytes.length > MAX_CURSOR_LENGTH) {
                throw invalid();
            }
            Map<String, Object> payload = JSON.readValue(new String(bytes, StandardCharsets.UTF_8), new TypeReference<>() { });
            if (!Integer.valueOf(VERSION).equals(intValue(payload, "version"))
                    || !expectedKind.name().equals(stringValue(payload, "cursorKind"))
                    || !expectedFingerprint.equals(stringValue(payload, "filterFingerprint"))) {
                throw invalid();
            }
            return payload;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static Long longValue(Map<String, Object> values, String name) {
        Object value = values.get(name);
        try {
            long number = value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
            if (number <= 0 || !String.valueOf(number).equals(String.valueOf(value)) && !(value instanceof Number)) {
                throw invalid();
            }
            return number;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static Integer intValue(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Number number)) {
            throw invalid();
        }
        return number.intValue();
    }

    private static String stringValue(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw invalid();
        }
        return string;
    }

    private static void requirePositive(Long value) {
        if (value == null || value <= 0) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(400, "Invalid cursor");
    }
}
