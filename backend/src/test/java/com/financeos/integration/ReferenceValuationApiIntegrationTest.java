package com.financeos.integration;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.fx.provider.ExchangeRateProvider;
import com.financeos.module.asset.marketdata.service.MarketDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "market-data.enabled=false",
        "fx-data.enabled=false"
})
class ReferenceValuationApiIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssetMapper assetMapper;

    @MockBean
    private MarketDataProvider marketDataProvider;

    @MockBean
    private ExchangeRateProvider exchangeRateProvider;

    @Test
    void getReturnsCachedReferenceValuationWithoutCallingProviders() throws Exception {
        LoggedInUser owner = registerAndLogin("reference-get-owner");
        Asset asset = asset(owner.userId(), "AAPL");
        assetMapper.insert(asset);
        insertFreshUsdQuote(asset.getSymbol());
        insertFreshUsdCnyRate();

        mockMvc.perform(get("/api/v1/assets/{id}", asset.getId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.referenceValuation.quoteCurrency").value("USD"))
                .andExpect(jsonPath("$.data.referenceValuation.fxBaseCurrency").value("USD"))
                .andExpect(jsonPath("$.data.referenceValuation.fxQuoteCurrency").value("CNY"))
                .andExpect(jsonPath("$.data.referenceValuation.valuationFreshness").value("FRESH"));

        verifyNoInteractions(marketDataProvider, exchangeRateProvider);
    }

    @Test
    void refreshUsesDisabledFxStaleFallbackWithoutChangingManualAssetFields() throws Exception {
        LoggedInUser owner = registerAndLogin("reference-stale-owner");
        Asset asset = asset(owner.userId(), "MSFT");
        assetMapper.insert(asset);
        insertFreshUsdQuote(asset.getSymbol());
        jdbcTemplate.update("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider)
                VALUES ('USD', 'CNY', 7.2, CURRENT_TIMESTAMP - INTERVAL '2 hours',
                        CURRENT_TIMESTAMP - INTERVAL '2 hours', 'TEST_FX')
                """);
        BigDecimal originalPrice = asset.getCurrentPrice();
        BigDecimal originalValue = asset.getMarketValue();

        mockMvc.perform(post("/api/v1/assets/{id}/reference-valuation/refresh", asset.getId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valuationFreshness").value("STALE"))
                .andExpect(jsonPath("$.data.fxFreshness").value("STALE"))
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'FEATURE_DISABLED')]").exists());

        Asset saved = assetMapper.selectById(asset.getId());
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo(originalPrice);
        assertThat(saved.getMarketValue()).isEqualByComparingTo(originalValue);
        verifyNoInteractions(marketDataProvider, exchangeRateProvider);
    }

    @Test
    void refreshWithMissingFxReturnsSanitizedServiceUnavailableAndChecksOwnershipFirst() throws Exception {
        LoggedInUser owner = registerAndLogin("reference-owner");
        LoggedInUser other = registerAndLogin("reference-other");
        Asset owned = asset(owner.userId(), "NVDA");
        Asset foreign = asset(other.userId(), "TSLA");
        assetMapper.insert(owned);
        assetMapper.insert(foreign);
        insertFreshUsdQuote(owned.getSymbol());

        mockMvc.perform(post("/api/v1/assets/{id}/reference-valuation/refresh", foreign.getId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        verifyNoInteractions(marketDataProvider, exchangeRateProvider);

        mockMvc.perform(post("/api/v1/assets/{id}/reference-valuation/refresh", owned.getId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("FX data is temporarily unavailable"));
        verifyNoInteractions(marketDataProvider, exchangeRateProvider);
    }

    private void insertFreshUsdQuote(String symbol) {
        jdbcTemplate.update("""
                INSERT INTO market_quotes (market, symbol, currency, price, quote_time, fetched_at, provider, created_at, updated_at)
                VALUES ('US', ?, 'USD', 20.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'TEST_QUOTE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, symbol);
    }

    private void insertFreshUsdCnyRate() {
        jdbcTemplate.update("""
                INSERT INTO exchange_rates (base_currency, quote_currency, rate, rate_time, fetched_at, provider)
                VALUES ('USD', 'CNY', 7.2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'TEST_FX')
                """);
    }

    private Asset asset(Long userId, String symbol) {
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName("Reference valuation " + UUID.randomUUID());
        asset.setSymbol(symbol);
        asset.setType("STOCK");
        asset.setMarket("US");
        asset.setCurrency("CNY");
        asset.setQuantity(BigDecimal.TEN);
        asset.setAvgCost(BigDecimal.valueOf(10));
        asset.setCurrentPrice(BigDecimal.valueOf(11));
        asset.setMarketValue(BigDecimal.valueOf(110));
        return asset;
    }

    private LoggedInUser registerAndLogin(String prefix) throws Exception {
        String username = prefix.substring(0, Math.min(prefix.length(), 8))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        mockMvc.perform(post("/api/v1/register").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/v1/login").contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        com.fasterxml.jackson.databind.JsonNode response = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(login.getResponse().getContentAsByteArray());
        return new LoggedInUser(response.at("/data/token").asText(), response.at("/data/userId").asLong());
    }

    private record LoggedInUser(String token, Long userId) {
    }
}
