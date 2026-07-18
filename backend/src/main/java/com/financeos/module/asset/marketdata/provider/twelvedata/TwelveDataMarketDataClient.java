package com.financeos.module.asset.marketdata.provider.twelvedata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import com.financeos.module.asset.marketdata.service.MarketDataErrorType;
import com.financeos.module.asset.marketdata.service.MarketDataProvider;
import com.financeos.module.asset.marketdata.service.MarketDataProviderException;
import com.financeos.module.asset.marketdata.service.MarketDataQuote;
import org.springframework.http.HttpStatusCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Component
public class TwelveDataMarketDataClient implements MarketDataProvider {
    private static final String MARKET = "US";
    private static final String PROVIDER = "TWELVE_DATA";
    private static final DateTimeFormatter TWELVE_DATA_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MarketDataProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TwelveDataMarketDataClient(@Qualifier("twelveDataRestClient") RestClient restClient,
                                      MarketDataProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public MarketDataQuote fetchQuote(String symbol) {
        if (!properties.isEnabled()) {
            throw new MarketDataProviderException(MarketDataErrorType.DISABLED, "Market data is disabled");
        }

        String normalizedSymbol = normalizeSymbol(symbol);
        String responseBody = retrieveQuote(normalizedSymbol);
        TwelveDataQuotePayload payload = parsePayload(responseBody);
        return toMarketDataQuote(payload, normalizedSymbol);
    }

    private String retrieveQuote(String symbol) {
        try {
            String body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/quote").queryParam("symbol", symbol).build())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw exceptionForStatus(response.getStatusCode());
                    })
                    .body(String.class);
            if (!StringUtils.hasText(body)) {
                throw new MarketDataProviderException(MarketDataErrorType.RESPONSE_FORMAT,
                        "Market data provider returned an empty response");
            }
            return body;
        } catch (MarketDataProviderException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new MarketDataProviderException(MarketDataErrorType.TRANSPORT,
                    "Market data provider request failed", exception);
        } catch (RestClientException exception) {
            throw new MarketDataProviderException(MarketDataErrorType.PROVIDER_UNAVAILABLE,
                    "Market data provider request failed", exception);
        }
    }

    private TwelveDataQuotePayload parsePayload(String responseBody) {
        try {
            TwelveDataQuotePayload payload = objectMapper.readValue(responseBody, TwelveDataQuotePayload.class);
            if (payload == null) {
                throw new MarketDataProviderException(MarketDataErrorType.RESPONSE_FORMAT,
                        "Market data provider returned an empty response");
            }
            if (isProviderError(payload)) {
                throw exceptionForProviderCode(payload.code());
            }
            return payload;
        } catch (MarketDataProviderException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new MarketDataProviderException(MarketDataErrorType.RESPONSE_FORMAT,
                    "Market data provider returned an invalid response", exception);
        }
    }

    private boolean isProviderError(TwelveDataQuotePayload payload) {
        return payload.code() != null || "error".equalsIgnoreCase(payload.status());
    }

    private MarketDataQuote toMarketDataQuote(TwelveDataQuotePayload payload, String requestedSymbol) {
        if (!StringUtils.hasText(payload.symbol()) || !requestedSymbol.equals(normalizeSymbol(payload.symbol()))) {
            throw new MarketDataProviderException(MarketDataErrorType.INVALID_QUOTE,
                    "Market data provider returned an invalid symbol");
        }
        if (!StringUtils.hasText(payload.currency())) {
            throw new MarketDataProviderException(MarketDataErrorType.INVALID_QUOTE,
                    "Market data provider returned a quote without currency");
        }
        BigDecimal price = payload.close();
        if (price == null || price.signum() <= 0) {
            throw new MarketDataProviderException(MarketDataErrorType.INVALID_QUOTE,
                    "Market data provider returned an invalid price");
        }
        return new MarketDataQuote(MARKET, requestedSymbol, payload.currency().trim().toUpperCase(Locale.ROOT),
                price, parseQuoteTime(payload), PROVIDER);
    }

    private Instant parseQuoteTime(TwelveDataQuotePayload payload) {
        if (payload.timestamp() != null) {
            return Instant.ofEpochSecond(payload.timestamp());
        }
        if (!StringUtils.hasText(payload.datetime())) {
            throw new MarketDataProviderException(MarketDataErrorType.INVALID_QUOTE,
                    "Market data provider returned a quote without time");
        }
        try {
            return Instant.parse(payload.datetime());
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(payload.datetime()).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(payload.datetime(), TWELVE_DATA_DATETIME).toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException exception) {
                    throw new MarketDataProviderException(MarketDataErrorType.INVALID_QUOTE,
                            "Market data provider returned an invalid quote time", exception);
                }
            }
        }
    }

    private String normalizeSymbol(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            throw new MarketDataProviderException(MarketDataErrorType.INVALID_QUOTE,
                    "Market data symbol is required");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private MarketDataProviderException exceptionForStatus(HttpStatusCode statusCode) {
        return exceptionForProviderCode(statusCode.value());
    }

    private MarketDataProviderException exceptionForProviderCode(Integer code) {
        MarketDataErrorType errorType = switch (code == null ? 0 : code) {
            case 400 -> MarketDataErrorType.INVALID_REQUEST;
            case 401, 403 -> MarketDataErrorType.AUTHENTICATION;
            case 404 -> MarketDataErrorType.NOT_FOUND;
            case 429 -> MarketDataErrorType.RATE_LIMITED;
            case 500, 501, 502, 503, 504 -> MarketDataErrorType.UPSTREAM_ERROR;
            default -> MarketDataErrorType.PROVIDER_UNAVAILABLE;
        };
        return new MarketDataProviderException(errorType, "Market data provider request was unsuccessful");
    }
}
