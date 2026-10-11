package com.financeos.module.investment.instrument.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.instrument.InstrumentStatus;
import com.financeos.module.investment.instrument.InvestmentAssetClass;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the immutable Account + Instrument identity needed by a future investment command.
 * Phase 2B-2 intentionally does not create an Asset projection without an investment fact.
 */
@Service
public class InvestmentPositionBindingService {
    private static final Set<InvestmentAssetClass> BROKERAGE_CLASSES = Set.of(
            InvestmentAssetClass.STOCK, InvestmentAssetClass.ETF,
            InvestmentAssetClass.FUND, InvestmentAssetClass.BOND);

    private final InvestmentInstrumentQueryService instrumentQueryService;
    private final AccountQueryService accountQueryService;
    private final AssetMapper assetMapper;

    public InvestmentPositionBindingService(InvestmentInstrumentQueryService instrumentQueryService,
                                            AccountQueryService accountQueryService,
                                            AssetMapper assetMapper) {
        this.instrumentQueryService = instrumentQueryService;
        this.accountQueryService = accountQueryService;
        this.assetMapper = assetMapper;
    }

    /** Caller must hold the owned Account row lock through its metadata update. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateAccountTypeChange(Long userId, Long accountId, String accountType) {
        List<String> supportedClasses = Arrays.stream(InvestmentAssetClass.values())
                .filter(assetClass -> isSupportedAccountClass(accountType, assetClass))
                .map(Enum::name)
                .toList();
        // CLOSED bindings remain immutable and can be reopened by historical corrections.
        // LEGACY bindings may still be explicitly remapped during opening migration.
        if (assetMapper.existsIncompatibleTransactionDrivenPosition(userId, accountId, supportedClasses)) {
            throw new BusinessException(409, "账户类型与已有投资仓位绑定不兼容");
        }
    }

    public ValidatedPositionBinding validate(Long userId, Long accountId, Long instrumentId) {
        requirePositiveId(userId, "userId");
        requirePositiveId(accountId, "accountId");
        requirePositiveId(instrumentId, "instrumentId");

        InvestmentInstrument instrument = instrumentQueryService.findByUserIdAndId(userId, instrumentId);
        if (instrument == null) {
            throw new BusinessException(404, "Investment instrument not found");
        }
        Account account = accountQueryService.findAccessibleAccount(userId, accountId);
        if (account == null) {
            throw new BusinessException(404, "Account not found");
        }
        if (instrument.getStatus() != InstrumentStatus.ACTIVE) {
            throw new BusinessException(409, "Inactive investment instrument cannot be bound");
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BusinessException(409, "Inactive account cannot be bound");
        }
        if (!"CNY".equals(account.getCurrency())) {
            throw new BusinessException(400, "Investment accounts must use CNY in the current version");
        }
        if (!isSupportedAccountClass(account.getType(), instrument.getAssetClass())) {
            throw new BusinessException(400, "Account type does not support the investment asset class");
        }
        return new ValidatedPositionBinding(account, instrument);
    }

    private boolean isSupportedAccountClass(String accountType, InvestmentAssetClass assetClass) {
        if (accountType == null || assetClass == null) {
            return false;
        }
        return switch (accountType.trim().toUpperCase(Locale.ROOT)) {
            case "BROKERAGE" -> BROKERAGE_CLASSES.contains(assetClass);
            case "CRYPTO_WALLET" -> assetClass == InvestmentAssetClass.CRYPTO;
            default -> false;
        };
    }

    private void requirePositiveId(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BusinessException(400, name + " must be positive");
        }
    }

    public record ValidatedPositionBinding(Account account, InvestmentInstrument instrument) {
    }
}
