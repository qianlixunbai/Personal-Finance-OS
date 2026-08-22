package com.financeos.module.importing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.module.importing.entity.TransactionImportSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionImportPreviewTokenServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void issuedTokenCarriesIssuedExpiryAndDeterministicWarningSetDigest() throws Exception {
        TransactionImportPreviewTokenService service = new TransactionImportPreviewTokenService(objectMapper,
                "transaction-import-preview-token-test-secret");
        TransactionImportSession session = session(Instant.now().plusSeconds(60));

        String first = service.issue(session, List.of("warning-b", "warning-a", "warning-a"));
        String second = service.issue(session, List.of("warning-a", "warning-b"));
        Map<String, Object> claims = payload(first);

        assertThat(claims).containsKeys("issuedAt", "expiresAt", "warningSetDigest");
        assertThat(payload(second).get("warningSetDigest")).isEqualTo(claims.get("warningSetDigest"));
    }

    @Test
    void rejectsATokenWhoseOwnExpiryHasElapsedEvenWhenTheSessionBindingStillMatches() {
        TransactionImportPreviewTokenService service = new TransactionImportPreviewTokenService(objectMapper,
                "transaction-import-preview-token-test-secret");
        TransactionImportSession session = session(Instant.now().minusSeconds(1));
        String token = service.issue(session, List.of());

        assertThatThrownBy(() -> service.verify(token, session.getUserId(), session))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
    }

    @Test
    void rejectsAnExpiredTokenAndAWarningSetDifferentFromTheSignedPreview() {
        Instant issuedAt = Instant.parse("2026-08-22T00:00:00Z");
        TransactionImportSession session = session(issuedAt.plusSeconds(30));
        String secret = "transaction-import-preview-token-test-secret";
        TransactionImportPreviewTokenService issuer = new TransactionImportPreviewTokenService(objectMapper, secret,
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        String token = issuer.issue(session, List.of("warning-a"));
        TransactionImportPreviewTokenService beforeExpiry = new TransactionImportPreviewTokenService(objectMapper, secret,
                Clock.fixed(issuedAt.plusSeconds(1), ZoneOffset.UTC));
        TransactionImportPreviewTokenService afterExpiry = new TransactionImportPreviewTokenService(objectMapper, secret,
                Clock.fixed(issuedAt.plusSeconds(31), ZoneOffset.UTC));

        assertThatThrownBy(() -> beforeExpiry.verify(token, session.getUserId(), session, List.of("warning-b")))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
        assertThatThrownBy(() -> afterExpiry.verify(token, session.getUserId(), session, List.of("warning-a")))
                .isInstanceOf(BusinessException.class).hasMessage("IMPORT_PREVIEW_STALE");
    }

    private Map<String, Object> payload(String token) throws Exception {
        String encoded = token.substring(0, token.indexOf('.'));
        return objectMapper.readValue(Base64.getUrlDecoder().decode(encoded), new TypeReference<>() { });
    }

    private TransactionImportSession session(Instant expiresAt) {
        TransactionImportSession session = new TransactionImportSession();
        session.setId(UUID.randomUUID());
        session.setUserId(1L);
        session.setPreallocatedBatchId(UUID.randomUUID());
        session.setRevision(2);
        session.setFileDigest("a".repeat(64));
        session.setMappingDigest("b".repeat(64));
        session.setOptionsDigest("c".repeat(64));
        session.setNormalizedRowsDigest("d".repeat(64));
        session.setExpiresAt(expiresAt);
        return session;
    }
}
