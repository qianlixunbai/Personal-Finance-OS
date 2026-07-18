package com.financeos.module.asset.marketdata.provider.twelvedata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.asset.marketdata.config.MarketDataProperties;
import com.financeos.module.asset.marketdata.service.MarketDataErrorType;
import com.financeos.module.asset.marketdata.service.MarketDataProviderException;
import com.financeos.module.asset.marketdata.service.MarketDataQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TwelveDataMarketDataClientTest {
    private static final String API_KEY = "test-api-key-not-a-secret";
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private TwelveDataMarketDataClient client;

    @BeforeEach
    void setUp() {
        MarketDataProperties properties = new MarketDataProperties();
        properties.setEnabled(true);
        properties.setApiKey(API_KEY);
        builder = RestClient.builder().baseUrl("https://api.twelvedata.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "apikey " + API_KEY);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TwelveDataMarketDataClient(builder.build(), properties, new ObjectMapper());
    }

    @Test
    void fetchesOneNormalizedQuoteWithAuthorizationHeaderAndUtcInstant() {
        server.expect(once(), requestTo("https://api.twelvedata.com/quote?symbol=AAPL"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "apikey " + API_KEY))
                .andRespond(withSuccess("""
                        {"symbol":"AAPL","currency":"usd","close":"212.34000000","timestamp":1767225600}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        MarketDataQuote quote = client.fetchQuote("  aapl ");

        assertThat(quote.market()).isEqualTo("US");
        assertThat(quote.symbol()).isEqualTo("AAPL");
        assertThat(quote.currency()).isEqualTo("USD");
        assertThat(quote.price()).isEqualByComparingTo("212.34000000");
        assertThat(quote.quoteTime()).isEqualTo(Instant.ofEpochSecond(1767225600));
        assertThat(quote.provider()).isEqualTo("TWELVE_DATA");
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("httpFailures")
    void mapsHttpFailuresToSemanticExceptions(HttpStatus status, MarketDataErrorType expectedType) {
        server.expect(requestTo("https://api.twelvedata.com/quote?symbol=AAPL"))
                .andRespond(withStatus(status).body("provider error"));

        assertFailureType(() -> client.fetchQuote("AAPL"), expectedType);
        server.verify();
    }

    @Test
    void mapsProviderErrorObjectWithoutExposingItsRawMessage() {
        server.expect(requestTo("https://api.twelvedata.com/quote?symbol=AAPL"))
                .andRespond(withSuccess("""
                        {"code":429,"status":"error","message":"raw provider diagnostic"}
                        """, org.springframework.http.MediaType.APPLICATION_JSON));

        MarketDataProviderException exception = failureFrom(() -> client.fetchQuote("AAPL"));

        assertThat(exception.getErrorType()).isEqualTo(MarketDataErrorType.RATE_LIMITED);
        assertThat(exception).hasMessageNotContaining("raw provider diagnostic").hasMessageNotContaining(API_KEY);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void rejectsMalformedOrInvalidQuotes(String responseBody, MarketDataErrorType expectedType) {
        server.expect(requestTo("https://api.twelvedata.com/quote?symbol=AAPL"))
                .andRespond(withSuccess(responseBody, org.springframework.http.MediaType.APPLICATION_JSON));

        assertFailureType(() -> client.fetchQuote("AAPL"), expectedType);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void mapsTimeoutAndConnectionFailuresWithoutLeakingApiKey(IOException transportFailure) {
        server.expect(requestTo("https://api.twelvedata.com/quote?symbol=AAPL"))
                .andRespond(withException(transportFailure));

        MarketDataProviderException exception = failureFrom(() -> client.fetchQuote("AAPL"));

        assertThat(exception.getErrorType()).isEqualTo(MarketDataErrorType.TRANSPORT);
        assertThat(exception).hasMessageNotContaining(API_KEY);
        server.verify();
    }

    @Test
    void rejectsCallsWhenFeatureIsDisabledWithoutSendingARequest() {
        MarketDataProperties disabledProperties = new MarketDataProperties();
        disabledProperties.setEnabled(false);
        TwelveDataMarketDataClient disabledClient = new TwelveDataMarketDataClient(builder.build(), disabledProperties,
                new ObjectMapper());

        assertFailureType(() -> disabledClient.fetchQuote("AAPL"), MarketDataErrorType.DISABLED);
        server.verify();
    }

    private static Stream<Arguments> httpFailures() {
        return Stream.of(
                Arguments.of(HttpStatus.UNAUTHORIZED, MarketDataErrorType.AUTHENTICATION),
                Arguments.of(HttpStatus.FORBIDDEN, MarketDataErrorType.AUTHENTICATION),
                Arguments.of(HttpStatus.BAD_REQUEST, MarketDataErrorType.INVALID_REQUEST),
                Arguments.of(HttpStatus.NOT_FOUND, MarketDataErrorType.NOT_FOUND),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, MarketDataErrorType.RATE_LIMITED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, MarketDataErrorType.UPSTREAM_ERROR)
        );
    }

    private static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of("{", MarketDataErrorType.RESPONSE_FORMAT),
                Arguments.of("", MarketDataErrorType.RESPONSE_FORMAT),
                Arguments.of("{\"symbol\":\"AAPL\",\"currency\":\"USD\",\"timestamp\":1767225600}", MarketDataErrorType.INVALID_QUOTE),
                Arguments.of("{\"symbol\":\"AAPL\",\"currency\":\"USD\",\"close\":\"0\",\"timestamp\":1767225600}", MarketDataErrorType.INVALID_QUOTE),
                Arguments.of("{\"symbol\":\"AAPL\",\"currency\":\"USD\",\"close\":\"-1\",\"timestamp\":1767225600}", MarketDataErrorType.INVALID_QUOTE),
                Arguments.of("{\"symbol\":\"AAPL\",\"currency\":\"USD\",\"close\":\"1\"}", MarketDataErrorType.INVALID_QUOTE),
                Arguments.of("{\"symbol\":\"AAPL\",\"currency\":\"USD\",\"close\":\"1\",\"datetime\":\"not-a-time\"}", MarketDataErrorType.INVALID_QUOTE),
                Arguments.of("{\"currency\":\"USD\",\"close\":\"1\",\"timestamp\":1767225600}", MarketDataErrorType.INVALID_QUOTE),
                Arguments.of("{\"symbol\":\"AAPL\",\"close\":\"1\",\"timestamp\":1767225600}", MarketDataErrorType.INVALID_QUOTE)
        );
    }

    private static Stream<Arguments> transportFailures() {
        return Stream.of(
                Arguments.of(new SocketTimeoutException("timeout")),
                Arguments.of(new ConnectException("connection refused"))
        );
    }

    private void assertFailureType(ThrowingCall call, MarketDataErrorType expectedType) {
        assertThat(failureFrom(call).getErrorType()).isEqualTo(expectedType);
    }

    private MarketDataProviderException failureFrom(ThrowingCall call) {
        MarketDataProviderException exception = catchThrowableOfType(call::run, MarketDataProviderException.class);
        assertThat(exception).isNotNull();
        return exception;
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
