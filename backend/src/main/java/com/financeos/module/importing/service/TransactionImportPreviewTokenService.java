package com.financeos.module.importing.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.module.importing.entity.TransactionImportSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class TransactionImportPreviewTokenService {
    public static final String CONTRACT_VERSION = "TRANSACTION_IMPORT_CONFIRM_V1";
    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public TransactionImportPreviewTokenService(ObjectMapper objectMapper,
                                                @Value("${finance.import.confirm.token-secret:}") String secret,
                                                @Qualifier("businessClock") Clock clock) {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException("Transaction import token secret must be explicitly configured and at least 32 characters");
        }
        this.objectMapper = objectMapper;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public TransactionImportPreviewTokenService(ObjectMapper objectMapper, String secret) {
        this(objectMapper, secret, Clock.systemUTC());
    }

    public String issue(TransactionImportSession session, List<String> warningIds) {
        try {
            Map<String, Object> claims = claims(session, warningIds);
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(claims));
            return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
        } catch (Exception exception) { throw new IllegalStateException("Cannot sign transaction import preview token", exception); }
    }

    public void verify(String token, Long userId, TransactionImportSession session) {
        verify(token, userId, session, null);
    }

    public void verify(String token, Long userId, TransactionImportSession session, List<String> expectedWarningIds) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(hmac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw stale();
            Map<String, Object> claims = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), new TypeReference<>() { });
            if (!String.valueOf(userId).equals(String.valueOf(claims.get("userId")))
                    || !session.getId().toString().equals(String.valueOf(claims.get("sessionId")))
                    || !session.getPreallocatedBatchId().toString().equals(String.valueOf(claims.get("batchId")))
                    || !String.valueOf(session.getRevision()).equals(String.valueOf(claims.get("revision")))
                    || !session.getFileDigest().equals(claims.get("fileDigest"))
                    || !session.getMappingDigest().equals(claims.get("mappingDigest"))
                    || !session.getOptionsDigest().equals(claims.get("optionsDigest"))
                    || !session.getNormalizedRowsDigest().equals(claims.get("normalizedRowsDigest"))
                    || !CONTRACT_VERSION.equals(claims.get("contractVersion"))
                    || !isValidLifetime(claims)
                    || (expectedWarningIds != null && !warningSetDigest(expectedWarningIds).equals(claims.get("warningSetDigest")))) throw stale();
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw stale(); }
    }

    private Map<String, Object> claims(TransactionImportSession s, List<String> warningIds) {
        Map<String, Object> claims = new TreeMap<>();
        Instant issuedAt = clock.instant();
        Instant expiresAt = s.getExpiresAt();
        claims.put("contractVersion", CONTRACT_VERSION);
        claims.put("userId", s.getUserId()); claims.put("sessionId", s.getId().toString()); claims.put("batchId", s.getPreallocatedBatchId().toString());
        claims.put("revision", s.getRevision()); claims.put("fileDigest", s.getFileDigest()); claims.put("mappingDigest", s.getMappingDigest());
        claims.put("optionsDigest", s.getOptionsDigest()); claims.put("normalizedRowsDigest", s.getNormalizedRowsDigest());
        claims.put("issuedAt", issuedAt.toString());
        claims.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        claims.put("warningSetDigest", warningSetDigest(warningIds));
        return claims;
    }

    private byte[] hmac(String value) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256")); return mac.doFinal(value.getBytes(StandardCharsets.UTF_8)); }
    private boolean isValidLifetime(Map<String, Object> claims) {
        try {
            Instant issuedAt = Instant.parse(String.valueOf(claims.get("issuedAt")));
            Instant expiresAt = Instant.parse(String.valueOf(claims.get("expiresAt")));
            Instant now = clock.instant();
            return !issuedAt.isAfter(expiresAt) && !now.isAfter(expiresAt);
        } catch (RuntimeException exception) {
            return false;
        }
    }
    private String warningSetDigest(List<String> warningIds) {
        String canonical = warningIds == null ? "" : warningIds.stream().distinct().sorted().collect(java.util.stream.Collectors.joining("|"));
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Cannot digest transaction import warnings", exception); }
    }
    private BusinessException stale() { return new BusinessException(409, "IMPORT_PREVIEW_STALE"); }
}
