package com.financeos.module.investment.migration;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.investment.instrument.InvestmentAssetClass;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.service.InvestmentInstrumentQueryService;
import com.financeos.module.investment.instrument.service.InvestmentPositionBindingService;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class LegacyAssetMigrationPreflightService {

    private final AssetMapper assetMapper;
    private final AccountQueryService accountQueryService;
    private final InvestmentInstrumentQueryService instrumentQueryService;
    private final InvestmentPositionBindingService positionBindingService;
    private final InvestmentTransactionMapper transactionMapper;
    private final LegacyAssetMigrationCostResolver costResolver;

    public LegacyAssetMigrationPreflightService(AssetMapper assetMapper,
                                                AccountQueryService accountQueryService,
                                                InvestmentInstrumentQueryService instrumentQueryService,
                                                InvestmentPositionBindingService positionBindingService,
                                                InvestmentTransactionMapper transactionMapper,
                                                LegacyAssetMigrationCostResolver costResolver) {
        this.assetMapper = assetMapper;
        this.accountQueryService = accountQueryService;
        this.instrumentQueryService = instrumentQueryService;
        this.positionBindingService = positionBindingService;
        this.transactionMapper = transactionMapper;
        this.costResolver = costResolver;
    }

    LegacyAssetMigrationPreflight preflight(Long userId, Long assetId, Long instrumentId, Long accountId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || !userId.equals(asset.getUserId())) {
            throw new BusinessException(404, "资产不存在");
        }
        InvestmentInstrument instrument = instrumentQueryService.findByUserIdAndId(userId, instrumentId);
        if (instrument == null) {
            throw new BusinessException(404, "Investment instrument not found");
        }
        Account account = accountQueryService.findAccessibleAccount(userId, accountId);
        if (account == null) {
            throw new BusinessException(404, "Account not found");
        }

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!"LEGACY".equals(asset.getPositionMode())) {
            errors.add("ASSET_NOT_LEGACY");
        }
        if (asset.getInstrumentId() != null) {
            errors.add("LEGACY_ASSET_ALREADY_BOUND_TO_INSTRUMENT");
        }
        if (!"CNY".equals(asset.getCurrency())) {
            errors.add("ASSET_CURRENCY_NOT_CNY");
        }
        if (asset.getQuantity() == null || asset.getQuantity().signum() <= 0) {
            errors.add("INVALID_QUANTITY");
        }
        if (asset.getRealizedProfitLoss() != null && asset.getRealizedProfitLoss().compareTo(BigDecimal.ZERO) != 0) {
            errors.add("LEGACY_REALIZED_PNL_NOT_ZERO");
        }
        if (asset.getPositionStatus() != null && !"OPEN".equals(asset.getPositionStatus())) {
            errors.add("LEGACY_POSITION_STATUS_NOT_OPEN");
        }
        InvestmentAssetClass assetClass = assetClass(asset.getType());
        if (assetClass == null) {
            errors.add("UNSUPPORTED_LEGACY_ASSET_TYPE");
        } else if (assetClass != instrument.getAssetClass()) {
            errors.add("INSTRUMENT_CLASS_DOES_NOT_MATCH_ASSET_TYPE");
        }
        if (transactionMapper.existsAnyByUserIdAndAssetId(userId, assetId)) {
            errors.add("ASSET_ALREADY_HAS_INVESTMENT_FACTS");
        }
        if (assetMapper.existsTransactionDrivenPosition(userId, accountId, instrumentId)) {
            errors.add("POSITION_ALREADY_EXISTS_FOR_ACCOUNT_AND_INSTRUMENT");
        }
        try {
            positionBindingService.validate(userId, accountId, instrumentId);
        } catch (BusinessException exception) {
            errors.add(bindingErrorCode(exception));
        }

        if (asset.getTotalCost() == null) {
            warnings.add("DERIVED_FROM_QUANTITY_AND_AVG_COST");
        }
        if (asset.getAccountId() == null) {
            warnings.add("LEGACY_ACCOUNT_NOT_SET");
        } else if (!asset.getAccountId().equals(accountId)) {
            warnings.add("ACCOUNT_REMAP_REQUIRED");
        }
        if (isBlank(asset.getName())) {
            warnings.add("LEGACY_NAME_MISSING");
        }
        if (isBlank(asset.getSymbol())) {
            warnings.add("LEGACY_SYMBOL_MISSING");
        }
        if (isBlank(asset.getMarket())) {
            warnings.add("LEGACY_MARKET_MISSING");
        }
        if (!isBlank(asset.getSymbol()) && !asset.getSymbol().trim().equalsIgnoreCase(instrument.getSymbol())) {
            warnings.add("LEGACY_SYMBOL_DIFFERS_FROM_INSTRUMENT");
        }
        if (!isBlank(asset.getMarket()) && !asset.getMarket().trim().equalsIgnoreCase(instrument.getMarket())) {
            warnings.add("LEGACY_MARKET_DIFFERS_FROM_INSTRUMENT");
        }
        if (referenceValuationInvalid(asset)) {
            warnings.add("LEGACY_REFERENCE_VALUATION_INVALID");
        }

        LegacyOpeningCostResolution cost = costResolver.resolve(asset);
        errors.addAll(cost.blockingErrors());
        warnings.addAll(cost.warnings());
        String sourceVersion = LegacyMigrationHashes.sourceVersion(asset);
        String requestHash = cost.isReady()
                ? LegacyMigrationHashes.requestHash(userId, asset, accountId, instrumentId, sourceVersion, cost)
                : null;
        return new LegacyAssetMigrationPreflight(asset, account, instrument, cost, sourceVersion, requestHash,
                List.copyOf(errors), List.copyOf(warnings));
    }

    private InvestmentAssetClass assetClass(String type) {
        if (type == null) {
            return null;
        }
        try {
            return InvestmentAssetClass.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String bindingErrorCode(BusinessException exception) {
        return switch (exception.getCode()) {
            case 400 -> "INVALID_ACCOUNT_INSTRUMENT_BINDING";
            case 409 -> "INACTIVE_ACCOUNT_OR_INSTRUMENT";
            default -> "INVALID_ACCOUNT_INSTRUMENT_BINDING";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private boolean referenceValuationInvalid(Asset asset) {
        if (asset.getCurrentPrice() == null || asset.getMarketValue() == null
                || isNegative(asset.getCurrentPrice()) || isNegative(asset.getMarketValue())) {
            return true;
        }
        return asset.getCurrentPrice().multiply(asset.getQuantity()).compareTo(asset.getMarketValue()) != 0;
    }
}
