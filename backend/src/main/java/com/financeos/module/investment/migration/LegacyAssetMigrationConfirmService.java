package com.financeos.module.investment.migration;

import com.financeos.common.BusinessException;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import com.financeos.module.investment.migration.dto.LegacyAssetMigrationConfirmResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class LegacyAssetMigrationConfirmService {
    private final MigrationPreviewTokenService tokenService;
    private final InvestmentTransactionMapper transactionMapper;
    private final LegacyAssetMigrationTransactionalService transactionalService;

    public LegacyAssetMigrationConfirmService(MigrationPreviewTokenService tokenService,
                                              InvestmentTransactionMapper transactionMapper,
                                              LegacyAssetMigrationTransactionalService transactionalService) {
        this.tokenService = tokenService;
        this.transactionMapper = transactionMapper;
        this.transactionalService = transactionalService;
    }

    public LegacyAssetMigrationConfirmResponse confirm(Long userId, Long assetId, String previewToken, String idempotencyKey) {
        String key = canonicalIdempotencyKey(idempotencyKey);
        PreviewTokenPayload token = tokenService.verify(previewToken);
        if (token.userId() != userId) {
            throw new BusinessException(409, "Migration preview token belongs to another user");
        }
        if (token.assetId() != assetId) {
            throw new BusinessException(409, "Migration preview does not match the asset path");
        }
        InvestmentTransaction existing = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
        if (existing != null) {
            return replayOrConflict(existing, token);
        }
        try {
            LegacyAssetMigrationTransactionalService.MigrationSuccess success = transactionalService.confirm(userId, token, key);
            return new LegacyAssetMigrationConfirmResponse(success.assetId(), success.transactionId(), success.accountId(),
                    success.instrumentId(), success.requestHash(), false);
        } catch (DataIntegrityViolationException exception) {
            InvestmentTransaction concurrent = transactionMapper.findByUserIdAndIdempotencyKey(userId, key);
            if (concurrent != null) {
                return replayOrConflict(concurrent, token);
            }
            throw exception;
        }
    }

    private LegacyAssetMigrationConfirmResponse replayOrConflict(InvestmentTransaction transaction, PreviewTokenPayload token) {
        if (!token.requestHash().equals(transaction.getRequestHash()) || token.assetId() != transaction.getAssetId()) {
            throw new BusinessException(409, "Idempotency key was already used for a different migration request");
        }
        return new LegacyAssetMigrationConfirmResponse(transaction.getAssetId(), transaction.getId(), transaction.getAccountId(),
                token.instrumentId(), transaction.getRequestHash(), true);
    }

    private String canonicalIdempotencyKey(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 100) {
            throw new BusinessException(400, "X-Idempotency-Key must be between 1 and 100 characters");
        }
        return value.trim();
    }
}
