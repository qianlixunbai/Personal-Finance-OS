package com.financeos.module.investment.read.cursor;

import com.financeos.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorFoundationSmokeTest {

    @Test
    void exposesTheVersionedInvestmentReadCursorCodec() {
        assertThatCode(() -> Class.forName("com.financeos.module.investment.read.cursor.CursorCodec"))
                .doesNotThrowAnyException();
    }

    @Test
    void roundTripsBothCursorKindsWithoutMakingThemInterchangeable() {
        String fingerprint = "a".repeat(64);
        String position = CursorCodec.encodePosition(new PositionCursor(9L, 8L, 7L), fingerprint);
        String transaction = CursorCodec.encodeLogicalTransaction(
                new LogicalTransactionCursor(Instant.parse("2026-08-01T10:15:30Z"), 11L), fingerprint);

        assertThat(CursorCodec.decodePosition(position, fingerprint)).isEqualTo(new PositionCursor(9L, 8L, 7L));
        assertThat(CursorCodec.decodeLogicalTransaction(transaction, fingerprint))
                .isEqualTo(new LogicalTransactionCursor(Instant.parse("2026-08-01T10:15:30Z"), 11L));
        assertInvalid(() -> CursorCodec.decodePosition(transaction, fingerprint));
        assertInvalid(() -> CursorCodec.decodeLogicalTransaction(position, fingerprint));
    }

    @Test
    void rejectsMalformedOversizedAndFilterMismatchedCursors() {
        String fingerprint = "a".repeat(64);
        String cursor = CursorCodec.encodePosition(new PositionCursor(1L, 2L, 3L), fingerprint);

        assertInvalid(() -> CursorCodec.decodePosition("not-base64", fingerprint));
        assertInvalid(() -> CursorCodec.decodePosition("a".repeat(2049), fingerprint));
        assertInvalid(() -> CursorCodec.decodePosition(cursor, "b".repeat(64)));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode())
                .isEqualTo(400);
    }
}
