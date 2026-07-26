package com.financeos.module.investment.migration;

import com.financeos.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

@Service
public class MigrationPreviewTokenService {
    static final String FORMULA_VERSION = "LEGACY_OPENING_MIGRATION_V1";

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public MigrationPreviewTokenService(@Value("${migration.preview.secret}") String secret,
                                        @Value("${migration.preview.ttl:10m}") Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    MigrationPreviewTokenService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("migration.preview.secret must contain at least 32 characters");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("migration.preview.ttl must be positive");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
    }

    public CreatedPreviewToken create(long userId, long assetId, long instrumentId, long accountId,
                                      String sourceVersion, String requestHash) {
        long expiresAt = clock.instant().plus(ttl).getEpochSecond();
        PreviewTokenPayload payload = new PreviewTokenPayload(userId, assetId, instrumentId, accountId,
                sourceVersion, requestHash, FORMULA_VERSION, expiresAt);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(serialize(payload).getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(encodedPayload));
        return new CreatedPreviewToken(encodedPayload + "." + signature, expiresAt);
    }

    public PreviewTokenPayload verify(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(409, "Migration preview token is required");
        }
        String[] pieces = token.split("\\.", -1);
        if (pieces.length != 2 || !MessageDigest.isEqual(hmac(pieces[0]), decodeSignature(pieces[1]))) {
            throw new BusinessException(409, "Migration preview token is invalid");
        }
        try {
            String[] values = new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 8) {
                throw new IllegalArgumentException("invalid payload");
            }
            PreviewTokenPayload payload = new PreviewTokenPayload(Long.parseLong(values[0]), Long.parseLong(values[1]),
                    Long.parseLong(values[2]), Long.parseLong(values[3]), values[4], values[5], values[6],
                    Long.parseLong(values[7]));
            if (!FORMULA_VERSION.equals(payload.formulaVersion()) || clock.instant().getEpochSecond() > payload.expiresAtEpochSecond()) {
                throw new BusinessException(409, "Migration preview token has expired");
            }
            return payload;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(409, "Migration preview token is invalid");
        }
    }

    private String serialize(PreviewTokenPayload payload) {
        return String.join("|", String.valueOf(payload.userId()), String.valueOf(payload.assetId()),
                String.valueOf(payload.instrumentId()), String.valueOf(payload.accountId()), payload.sourceVersion(),
                payload.requestHash(), payload.formulaVersion(), String.valueOf(payload.expiresAtEpochSecond()));
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign migration preview token", exception);
        }
    }

    private byte[] decodeSignature(String signature) {
        try {
            return Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

    public record CreatedPreviewToken(String value, long expiresAtEpochSecond) {
    }
}
