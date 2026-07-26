package com.financeos.module.investment.instrument.service;

import com.financeos.common.BusinessException;
import com.financeos.module.investment.instrument.InstrumentStatus;
import com.financeos.module.investment.instrument.InvestmentAssetClass;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.mapper.InvestmentInstrumentMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class InvestmentInstrumentCommandService {
    static final String UNIQUE_CONSTRAINT = "uk_investment_instruments_user_market_symbol";
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9./:-]{0,29}$");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> MARKETS = Set.of(
            "US", "HK", "CN", "JP", "KR", "CRYPTO", "OTC", "FUND", "OTHER", "UNKNOWN");

    private final InvestmentInstrumentMapper instrumentMapper;

    public InvestmentInstrumentCommandService(InvestmentInstrumentMapper instrumentMapper) {
        this.instrumentMapper = instrumentMapper;
    }

    @Transactional
    public InvestmentInstrument create(Long userId, CreateInvestmentInstrumentCommand command) {
        requirePositiveId(userId, "userId");
        if (command == null) {
            throw new BusinessException(400, "Instrument command is required");
        }
        String symbol = canonicalSymbol(command.symbol());
        String market = canonicalMarket(command.market());
        String name = requiredTrimmed(command.name(), "Instrument name is required");
        String quoteCurrency = canonicalCurrency(command.quoteCurrency());
        InvestmentAssetClass assetClass = canonicalAssetClass(command.assetClass());

        if (instrumentMapper.findByUserIdAndMarketAndSymbol(userId, market, symbol) != null) {
            throw duplicateInstrument();
        }

        InvestmentInstrument instrument = new InvestmentInstrument();
        instrument.setUserId(userId);
        instrument.setSymbol(symbol);
        instrument.setName(name);
        instrument.setMarket(market);
        instrument.setAssetClass(assetClass);
        instrument.setQuoteCurrency(quoteCurrency);
        instrument.setStatus(InstrumentStatus.ACTIVE);
        try {
            instrumentMapper.insert(instrument);
        } catch (DataIntegrityViolationException exception) {
            if (KnownPostgresConstraintViolation.matches(exception, UNIQUE_CONSTRAINT)) {
                throw duplicateInstrument();
            }
            throw exception;
        }
        return instrument;
    }

    static String canonicalSymbol(String value) {
        String canonical = requiredTrimmed(value, "Instrument symbol is required").toUpperCase(Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(canonical).matches()) {
            throw new BusinessException(400, "Instrument symbol must use the canonical format");
        }
        return canonical;
    }

    static String canonicalMarket(String value) {
        String canonical = requiredTrimmed(value, "Instrument market is required").toUpperCase(Locale.ROOT);
        if (!MARKETS.contains(canonical)) {
            throw new BusinessException(400, "Instrument market is not supported");
        }
        return canonical;
    }

    private static String canonicalCurrency(String value) {
        String canonical = requiredTrimmed(value, "Instrument quote currency is required").toUpperCase(Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(canonical).matches()) {
            throw new BusinessException(400, "Instrument quote currency must be a three-letter code");
        }
        return canonical;
    }

    private static InvestmentAssetClass canonicalAssetClass(String value) {
        try {
            return InvestmentAssetClass.valueOf(
                    requiredTrimmed(value, "Instrument asset class is required").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "Instrument asset class is not supported");
        }
    }

    private static String requiredTrimmed(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(400, message);
        }
        return value.trim();
    }

    private static void requirePositiveId(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BusinessException(400, name + " must be positive");
        }
    }

    private static BusinessException duplicateInstrument() {
        return new BusinessException(409, "Investment instrument already exists for this user, market, and symbol");
    }
}
