package com.financeos.module.investment.migration;

import com.financeos.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationPreviewTokenServiceTest {
    private static final String SECRET = "a-test-migration-preview-secret-with-32-plus-characters";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void signsAndVerifiesTheFullImmutablePreviewBinding() {
        MigrationPreviewTokenService service = new MigrationPreviewTokenService(SECRET, Duration.ofMinutes(10), CLOCK);

        MigrationPreviewTokenService.CreatedPreviewToken created = service.create(7L, 8L, 9L, 10L, "source", "request");
        PreviewTokenPayload payload = service.verify(created.value());

        assertEquals(7L, payload.userId());
        assertEquals(8L, payload.assetId());
        assertEquals(9L, payload.instrumentId());
        assertEquals(10L, payload.accountId());
        assertEquals("source", payload.sourceVersion());
        assertEquals("request", payload.requestHash());
    }

    @Test
    void rejectsTamperedSignature() {
        MigrationPreviewTokenService service = new MigrationPreviewTokenService(SECRET, Duration.ofMinutes(10), CLOCK);
        String token = service.create(7L, 8L, 9L, 10L, "source", "request").value();

        assertThrows(BusinessException.class, () -> service.verify(token + "x"));
    }

    @Test
    void rejectsExpiredToken() {
        MigrationPreviewTokenService producer = new MigrationPreviewTokenService(SECRET, Duration.ofSeconds(1), CLOCK);
        MigrationPreviewTokenService verifier = new MigrationPreviewTokenService(SECRET, Duration.ofMinutes(10),
                Clock.fixed(Instant.parse("2026-07-26T00:01:00Z"), ZoneOffset.UTC));

        assertThrows(BusinessException.class, () -> verifier.verify(producer.create(7L, 8L, 9L, 10L, "source", "request").value()));
    }
}
