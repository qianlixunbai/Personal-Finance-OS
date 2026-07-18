package com.financeos.integration;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.mapper.AssetMapper;
import com.financeos.module.asset.marketdata.service.MarketDataProvider;
import com.financeos.module.asset.marketdata.service.MarketDataQuote;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "market-data.enabled=true",
        "market-data.api-key=integration-test-key"
})
class MarketQuoteRefreshApiIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssetMapper assetMapper;

    @MockBean
    private MarketDataProvider marketDataProvider;

    @Test
    void refreshesOnlyTheAuthenticatedOwnersAssetWithoutChangingAssetValuation() throws Exception {
        LoggedInUser owner = registerAndLogin("quote-owner");
        LoggedInUser other = registerAndLogin("quote-other");
        Asset ownedAsset = asset(owner.userId(), " AAPL ");
        Asset foreignAsset = asset(other.userId(), "MSFT");
        assetMapper.insert(ownedAsset);
        assetMapper.insert(foreignAsset);
        BigDecimal originalPrice = ownedAsset.getCurrentPrice();
        BigDecimal originalValue = ownedAsset.getMarketValue();

        when(marketDataProvider.fetchQuote("AAPL")).thenReturn(new MarketDataQuote("US", "AAPL", "USD",
                new BigDecimal("212.34"), Instant.parse("2026-07-18T00:00:00Z"), "TEST"));

        mockMvc.perform(post("/api/v1/assets/{id}/quote/refresh", ownedAsset.getId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.symbol").value("AAPL"))
                .andExpect(jsonPath("$.data.refreshResult").value("UPDATED"))
                .andExpect(jsonPath("$.data.price").value(212.34));

        Asset saved = assetMapper.selectById(ownedAsset.getId());
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo(originalPrice);
        assertThat(saved.getMarketValue()).isEqualByComparingTo(originalValue);

        reset(marketDataProvider);
        mockMvc.perform(post("/api/v1/assets/{id}/quote/refresh", foreignAsset.getId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        verifyNoInteractions(marketDataProvider);
    }

    @Test
    void rejectsUnauthenticatedRefreshRequests() throws Exception {
        mockMvc.perform(post("/api/v1/assets/{id}/quote/refresh", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void rejectsNonUsAssetsWithoutCallingProviderOrChangingValuation() throws Exception {
        LoggedInUser owner = registerAndLogin("non-us-quote-owner");
        Asset nonUsAsset = asset(owner.userId(), "0700");
        nonUsAsset.setType("ETF");
        nonUsAsset.setMarket("HK");
        assetMapper.insert(nonUsAsset);
        BigDecimal originalPrice = nonUsAsset.getCurrentPrice();
        BigDecimal originalValue = nonUsAsset.getMarketValue();

        mockMvc.perform(post("/api/v1/assets/{id}/quote/refresh", nonUsAsset.getId())
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(marketDataProvider);
        Asset saved = assetMapper.selectById(nonUsAsset.getId());
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo(originalPrice);
        assertThat(saved.getMarketValue()).isEqualByComparingTo(originalValue);
    }

    private Asset asset(Long userId, String symbol) {
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setName("Quote asset " + UUID.randomUUID());
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
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = prefix + suffix;
        mockMvc.perform(post("/api/v1/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + username
                                + "@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        MvcResult login = mockMvc.perform(post("/api/v1/login")
                        .contentType(MediaType.APPLICATION_JSON)
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
