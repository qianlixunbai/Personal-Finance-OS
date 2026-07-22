package com.financeos.module.asset.valuation.service;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.fx.entity.ExchangeRate;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateQueryService;
import com.financeos.module.asset.marketdata.fx.service.ExchangeRateFreshness;
import com.financeos.module.asset.valuation.dto.ReferenceValuationFreshness;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import com.financeos.module.asset.valuation.dto.ReferenceValuationWarning;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ReferenceValuationService {
    public static final String BASE_CURRENCY = "CNY";
    public static final String FORMULA_VERSION = "REFERENCE_VALUATION_V1";
    private static final String SYSTEM_IDENTITY = "SYSTEM_IDENTITY";

    private final Clock clock;
    private final ExchangeRateQueryService exchangeRateQueryService;

    public ReferenceValuationService(Clock clock) {
        this(null, clock);
    }

    @Autowired
    public ReferenceValuationService(ExchangeRateQueryService exchangeRateQueryService, Clock clock) {
        this.exchangeRateQueryService = exchangeRateQueryService;
        this.clock = clock;
    }

    /**
     * Reads all required FX snapshots in one query and performs in-memory calculations only.
     */
    public Map<Long, ReferenceValuationResponse> calculateAll(Collection<Asset> assets,
                                                               Map<String, MarketQuoteSnapshotResponse> quotes) {
        if (assets.isEmpty()) {
            return Map.of();
        }
        if (exchangeRateQueryService == null) {
            throw new IllegalStateException("Batch reference valuation requires exchange rate queries");
        }
        List<String> currencies = assets.stream()
                .map(Asset::getSymbol)
                .map(this::normalizedSymbol)
                .map(quotes::get)
                .filter(java.util.Objects::nonNull)
                .map(MarketQuoteSnapshotResponse::currency)
                .filter(java.util.Objects::nonNull)
                .map(currency -> currency.trim().toUpperCase(Locale.ROOT))
                .filter(currency -> !BASE_CURRENCY.equals(currency))
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream().toList();
        Map<String, ExchangeRate> rates = exchangeRateQueryService.findAll(currencies, BASE_CURRENCY).stream()
                .collect(Collectors.toMap(rate -> rate.getBaseCurrency().toUpperCase(Locale.ROOT), Function.identity(),
                        (first, ignored) -> first));
        return assets.stream().collect(Collectors.toMap(Asset::getId,
                asset -> {
                    MarketQuoteSnapshotResponse quote = quotes.get(normalizedSymbol(asset.getSymbol()));
                    ExchangeRate rate = quote == null || quote.currency() == null ? null
                            : rates.get(quote.currency().trim().toUpperCase(Locale.ROOT));
                    return calculate(asset, quote, rate);
                }));
    }

    public ReferenceValuationResponse calculate(Asset asset, MarketQuoteSnapshotResponse quote, ExchangeRate rate) {
        Instant calculatedAt = clock.instant();
        List<ReferenceValuationWarning> warnings = new ArrayList<>();
        if (asset.getQuantity() == null || asset.getQuantity().signum() < 0) {
            warnings.add(warning("INVALID_INPUT", "VALUATION", "Asset quantity is invalid."));
            return response(asset, quote, false, null, null, null, null, null, null,
                    quote == null ? MarketQuoteFreshness.NEVER_FETCHED : quote.freshness(),
                    ExchangeRateFreshness.NEVER_FETCHED, null, null,
                    ReferenceValuationFreshness.UNAVAILABLE, calculatedAt, warnings);
        }

        boolean quoteAvailable = quote != null && quote.price() != null && quote.price().signum() > 0;
        MarketQuoteFreshness quoteFreshness = quote == null ? MarketQuoteFreshness.NEVER_FETCHED : quote.freshness();
        if (!quoteAvailable) {
            warnings.add(warning("QUOTE_MISSING", "QUOTE", "No usable market quote is available."));
        } else if (quoteFreshness == MarketQuoteFreshness.STALE) {
            warnings.add(warning("QUOTE_STALE", "QUOTE", "The market quote is stale."));
        }

        boolean fxRequired = quoteAvailable && !BASE_CURRENCY.equalsIgnoreCase(quote.currency());
        boolean rateAvailable = rate != null && rate.getRate() != null && rate.getRate().signum() > 0;
        ExchangeRateFreshness fxFreshness = fxRequired || !quoteAvailable
                ? (rate == null ? ExchangeRateFreshness.NEVER_FETCHED : freshnessOf(rate))
                : ExchangeRateFreshness.FRESH;
        boolean fxAvailable = fxRequired ? rateAvailable : (quoteAvailable || rateAvailable);
        if (fxRequired && !fxAvailable) {
            warnings.add(warning("FX_MISSING", "FX", "No usable exchange rate is available."));
        } else if (fxRequired && fxFreshness == ExchangeRateFreshness.STALE) {
            warnings.add(warning("FX_STALE", "FX", "The exchange rate is stale."));
        }

        BigDecimal nativeValue = quoteAvailable ? asset.getQuantity().multiply(quote.price()) : null;
        BigDecimal fxRate = quoteAvailable && !fxRequired ? BigDecimal.ONE : (rateAvailable ? rate.getRate() : null);
        BigDecimal baseValue = nativeValue != null && fxRate != null
                ? nativeValue.multiply(fxRate).setScale(2, RoundingMode.HALF_UP)
                : null;
        ReferenceValuationFreshness valuationFreshness = valuationFreshness(quoteAvailable, fxAvailable,
                quoteFreshness, fxFreshness);

        return response(asset, quote, fxRequired,
                rateAvailable ? rate.getBaseCurrency() : (quoteAvailable ? BASE_CURRENCY : null),
                rateAvailable ? rate.getQuoteCurrency() : (quoteAvailable ? BASE_CURRENCY : null),
                fxRate,
                rateAvailable ? rate.getRateTime() : null,
                rateAvailable ? rate.getFetchedAt() : null,
                rateAvailable ? rate.getProvider() : (quoteAvailable && !fxRequired ? SYSTEM_IDENTITY : null),
                quoteFreshness, fxFreshness, nativeValue, baseValue, valuationFreshness, calculatedAt, warnings);
    }

    private ReferenceValuationFreshness valuationFreshness(boolean quoteAvailable, boolean fxAvailable,
                                                            MarketQuoteFreshness quoteFreshness,
                                                            ExchangeRateFreshness fxFreshness) {
        if (!quoteAvailable && !fxAvailable) {
            return ReferenceValuationFreshness.UNAVAILABLE;
        }
        if (!quoteAvailable || !fxAvailable) {
            return ReferenceValuationFreshness.PARTIAL;
        }
        return quoteFreshness == MarketQuoteFreshness.STALE || fxFreshness == ExchangeRateFreshness.STALE
                ? ReferenceValuationFreshness.STALE : ReferenceValuationFreshness.FRESH;
    }

    private ExchangeRateFreshness freshnessOf(ExchangeRate rate) {
        if (rate.getFetchedAt() == null) {
            return ExchangeRateFreshness.NEVER_FETCHED;
        }
        return exchangeRateQueryService == null ? ExchangeRateFreshness.FRESH : exchangeRateQueryService.freshnessOf(rate);
    }

    private ReferenceValuationResponse response(Asset asset, MarketQuoteSnapshotResponse quote, boolean fxRequired,
                                                 String fxBaseCurrency, String fxQuoteCurrency, BigDecimal fxRate,
                                                 Instant fxRateTime, Instant fxFetchedAt, String fxProvider,
                                                 MarketQuoteFreshness quoteFreshness, ExchangeRateFreshness fxFreshness,
                                                 BigDecimal nativeValue, BigDecimal baseValue,
                                                 ReferenceValuationFreshness valuationFreshness, Instant calculatedAt,
                                                 List<ReferenceValuationWarning> warnings) {
        return new ReferenceValuationResponse(asset.getId(), asset.getSymbol(), asset.getQuantity(),
                quote == null ? null : quote.currency(), quote == null ? null : quote.price(),
                quote == null ? null : quote.quoteTime(), quote == null ? null : quote.fetchedAt(),
                quote == null ? null : quote.provider(), quoteFreshness,
                fxRequired, fxBaseCurrency, fxQuoteCurrency, fxRate, fxRateTime, fxFetchedAt, fxProvider, fxFreshness,
                nativeValue, BASE_CURRENCY, baseValue, valuationFreshness, calculatedAt, FORMULA_VERSION, List.copyOf(warnings));
    }

    private ReferenceValuationWarning warning(String code, String component, String message) {
        return new ReferenceValuationWarning(code, component, message);
    }

    private String normalizedSymbol(String symbol) {
        return symbol == null ? null : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
