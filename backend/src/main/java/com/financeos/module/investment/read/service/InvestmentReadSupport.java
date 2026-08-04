package com.financeos.module.investment.read.service;

import com.financeos.common.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

final class InvestmentReadSupport {
    private InvestmentReadSupport() {
    }

    static int pageSize(Integer size) {
        int resolved = size == null ? 20 : size;
        if (resolved < 1 || resolved > 100) {
            throw new BusinessException(400, "size must be between 1 and 100");
        }
        return resolved;
    }

    static Long positive(String name, Long value) {
        if (value != null && value <= 0) {
            throw new BusinessException(400, name + " must be positive");
        }
        return value;
    }

    static String enumValue(String name, String value, String... allowed) {
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        for (String candidate : allowed) {
            if (candidate.equals(normalized)) {
                return normalized;
            }
        }
        throw new BusinessException(400, "Invalid " + name);
    }

    static String fingerprint(String canonicalFilter) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalFilter.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String decimal(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    static boolean supplied(String cursor) {
        return cursor != null && !cursor.isBlank();
    }

    static void notFoundIf(boolean owned) {
        if (!owned) {
            throw new BusinessException(404, "Investment resource not found");
        }
    }
}
