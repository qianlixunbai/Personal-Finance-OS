package com.financeos.module.asset.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.BusinessException;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.common.PageResult;
import com.financeos.module.asset.dto.AssetRequest;
import com.financeos.module.asset.dto.AssetResponse;
import com.financeos.module.asset.service.AssetService;
import com.financeos.module.asset.marketdata.dto.MarketQuoteFreshness;
import com.financeos.module.asset.marketdata.dto.MarketQuoteRefreshResult;
import com.financeos.module.asset.marketdata.dto.MarketQuoteResponse;
import com.financeos.module.asset.marketdata.dto.MarketQuoteSnapshotResponse;
import com.financeos.module.asset.marketdata.service.MarketQuoteService;
import com.financeos.module.asset.valuation.dto.ReferenceValuationResponse;
import com.financeos.module.asset.valuation.service.ReferenceValuationRefreshService;
import com.financeos.module.auth.config.SecurityConfig;
import com.financeos.module.auth.config.SecurityErrorResponseHandler;
import com.financeos.module.auth.util.JwtAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssetController.class)
@Import({SecurityConfig.class, SecurityErrorResponseHandler.class, GlobalExceptionHandler.class})
class AssetControllerWebMvcTest {

    private static final Long USER_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private AssetService assetService;

    @MockBean
    private MarketQuoteService marketQuoteService;

    @MockBean
    private ReferenceValuationRefreshService referenceValuationRefreshService;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthFilter).doFilter(any(), any(), any());
    }

    @Test
    void listWithoutAuthenticationReturnsUnifiedUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未认证或登录已过期"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(assetService);
    }

    @Test
    void listReturnsAssetResponsesForLongPrincipalWithNumericValuesAndIsoTimestamp() throws Exception {
        when(assetService.listByUser(USER_ID)).thenReturn(List.of(assetResponse(7L, "沪深300ETF")));

        MvcResult result = mockMvc.perform(get("/api/v1/assets").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").isNumber())
                .andExpect(jsonPath("$.data[0].quantity").isNumber())
                .andExpect(jsonPath("$.data[0].avgCost").isNumber())
                .andExpect(jsonPath("$.data[0].currentPrice").isNumber())
                .andExpect(jsonPath("$.data[0].marketValue").isNumber())
                .andExpect(jsonPath("$.data[0].marketQuote.symbol").value("AAPL"))
                .andExpect(jsonPath("$.data[0].marketQuote.freshness").value("FRESH"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-07-16T10:30:45"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/0/quantity").decimalValue()).isEqualByComparingTo("12.50000000");
        assertThat(json.at("/data/0/avgCost").decimalValue()).isEqualByComparingTo("10.2300");
        assertThat(json.at("/data/0/currentPrice").decimalValue()).isEqualByComparingTo("11.1100");
        assertThat(json.at("/data/0/marketValue").decimalValue()).isEqualByComparingTo("138.8750");
        verify(assetService).listByUser(USER_ID);
        verifyNoInteractions(marketQuoteService);
    }

    @Test
    void pageUsesDefaultParametersAndReturnsPageStructure() throws Exception {
        when(assetService.pageByUser(USER_ID, 1, 20))
                .thenReturn(new PageResult<>(List.of(assetResponse(7L, "沪深300ETF")), 1L, 1, 20));

        mockMvc.perform(get("/api/v1/assets/page").with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].marketQuote.symbol").value("AAPL"));

        verify(assetService).pageByUser(USER_ID, 1, 20);
        verifyNoInteractions(marketQuoteService);
    }

    @Test
    void createReturnsAssetAndForwardsValidatedRequestForLongPrincipal() throws Exception {
        when(assetService.create(eq(USER_ID), any(AssetRequest.class))).thenReturn(assetResponse(7L, "沪深300ETF"));

        MvcResult result = mockMvc.perform(post("/api/v1/assets")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"沪深300ETF","symbol":"510300","type":"ETF","market":"CN","currency":"CNY","quantity":12.5,"avgCost":10.23}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.quantity").isNumber())
                .andExpect(jsonPath("$.data.avgCost").isNumber())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/quantity").decimalValue()).isEqualByComparingTo("12.50000000");
        assertThat(json.at("/data/avgCost").decimalValue()).isEqualByComparingTo("10.2300");
        ArgumentCaptor<AssetRequest> requestCaptor = ArgumentCaptor.forClass(AssetRequest.class);
        verify(assetService).create(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new AssetRequest(
                "沪深300ETF", "510300", "ETF", "CN", "CNY",
                new BigDecimal("12.5"), new BigDecimal("10.23")));
    }

    @Test
    void createRejectsZeroQuantityWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/assets")
                        .with(authentication(currentUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"沪深300ETF","type":"ETF","quantity":0,"avgCost":10.23}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(assetService);
    }

    @Test
    void updatePriceForwardsAssetIdAndExactDecimalPrice() throws Exception {
        when(assetService.updatePrice(USER_ID, 7L, new BigDecimal("11.11"))).thenReturn(assetResponse(7L, "沪深300ETF"));

        MvcResult result = mockMvc.perform(put("/api/v1/assets/{id}/price", 7L)
                        .param("price", "11.11")
                        .with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.currentPrice").isNumber())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.at("/data/currentPrice").decimalValue()).isEqualByComparingTo("11.1100");
        verify(assetService).updatePrice(USER_ID, 7L, new BigDecimal("11.11"));
    }

    @Test
    void updatePriceRejectsNonPositivePriceWithoutCallingService() throws Exception {
        mockMvc.perform(put("/api/v1/assets/{id}/price", 7L)
                        .param("price", "0")
                        .with(authentication(currentUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(assetService);
    }

    @Test
    void getMapsMissingOrForeignAssetToNotFoundForCurrentUser() throws Exception {
        when(assetService.getById(USER_ID, 99L)).thenThrow(new BusinessException(404, "资产不存在"));

        mockMvc.perform(get("/api/v1/assets/{id}", 99L).with(authentication(currentUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("资产不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(assetService).getById(USER_ID, 99L);
        verifyNoInteractions(marketQuoteService);
    }

    @Test
    void getReturnsTheCachedMarketQuoteWithoutCallingTheRefreshService() throws Exception {
        when(assetService.getById(USER_ID, 7L)).thenReturn(assetResponse(7L, "沪深300ETF"));

        mockMvc.perform(get("/api/v1/assets/{id}", 7L).with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marketQuote.price").value(212.34))
                .andExpect(jsonPath("$.data.marketQuote.refreshResult").doesNotExist());

        verify(assetService).getById(USER_ID, 7L);
        verifyNoInteractions(marketQuoteService);
    }

    @Test
    void deleteMapsAssetWithOpenPositionToBusinessBadRequest() throws Exception {
        doThrow(new BusinessException(400, "该资产仍有持仓，无法删除"))
                .when(assetService).delete(USER_ID, 7L);

        mockMvc.perform(delete("/api/v1/assets/{id}", 7L).with(authentication(currentUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("该资产仍有持仓，无法删除"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(assetService).delete(USER_ID, 7L);
    }

    @Test
    void closeMapsTransactionDrivenProjectionProtectionToConflict() throws Exception {
        when(assetService.close(USER_ID, 7L)).thenThrow(new BusinessException(409,
                "Transaction-driven asset projections cannot be modified or deleted through the Asset API"));

        mockMvc.perform(put("/api/v1/assets/{id}/close", 7L).with(authentication(currentUser())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value(
                        "Transaction-driven asset projections cannot be modified or deleted through the Asset API"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(assetService).close(USER_ID, 7L);
    }

    @Test
    void refreshesOneOwnedAssetQuoteThroughTheDedicatedEndpoint() throws Exception {
        when(marketQuoteService.refresh(USER_ID, 7L)).thenReturn(quoteResponse());

        mockMvc.perform(post("/api/v1/assets/{id}/quote/refresh", 7L).with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.symbol").value("AAPL"))
                .andExpect(jsonPath("$.data.refreshResult").value("UPDATED"))
                .andExpect(jsonPath("$.data.warning").doesNotExist());

        verify(marketQuoteService).refresh(USER_ID, 7L);
    }

    @Test
    void refreshesReferenceValuationThroughItsDedicatedEndpointWithoutRequestBody() throws Exception {
        when(referenceValuationRefreshService.refresh(USER_ID, 7L)).thenReturn(referenceValuation());

        mockMvc.perform(post("/api/v1/assets/{id}/reference-valuation/refresh", 7L).with(authentication(currentUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.assetId").value(7))
                .andExpect(jsonPath("$.data.baseCurrency").value("CNY"))
                .andExpect(jsonPath("$.data.formulaVersion").value("REFERENCE_VALUATION_V1"));

        verify(referenceValuationRefreshService).refresh(USER_ID, 7L);
        verifyNoInteractions(marketQuoteService);
    }

    private UsernamePasswordAuthenticationToken currentUser() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, Collections.emptyList());
    }

    private AssetResponse assetResponse(Long id, String name) {
        return new AssetResponse(id, name, "510300", "ETF", "CN", "CNY",
                new BigDecimal("12.50000000"), new BigDecimal("10.2300"), new BigDecimal("11.1100"),
                new BigDecimal("138.8750"), new BigDecimal("11.0000"), new BigDecimal("8.6022"),
                LocalDateTime.of(2026, 7, 16, 10, 30, 45),
                new MarketQuoteSnapshotResponse("AAPL", "US", "USD", new BigDecimal("212.34"),
                        Instant.parse("2026-07-18T00:00:00Z"), Instant.parse("2026-07-18T00:01:00Z"),
                        "TWELVE_DATA", MarketQuoteFreshness.FRESH));
    }

    private MarketQuoteResponse quoteResponse() {
        return new MarketQuoteResponse("AAPL", "US", "USD", new BigDecimal("212.34"),
                Instant.parse("2026-07-18T00:00:00Z"), Instant.parse("2026-07-18T00:01:00Z"), "TWELVE_DATA",
                MarketQuoteFreshness.FRESH, MarketQuoteRefreshResult.UPDATED, null);
    }

    private ReferenceValuationResponse referenceValuation() {
        return new ReferenceValuationResponse(7L, "AAPL", new BigDecimal("2.00000000"),
                "CNY", new BigDecimal("10.00000000"), Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-22T00:01:00Z"), "TEST", MarketQuoteFreshness.FRESH,
                false, "CNY", "CNY", BigDecimal.ONE, null, null, "SYSTEM_IDENTITY",
                com.financeos.module.asset.marketdata.fx.service.ExchangeRateFreshness.FRESH,
                new BigDecimal("20.0000000000000000"), "CNY", new BigDecimal("20.00"),
                com.financeos.module.asset.valuation.dto.ReferenceValuationFreshness.FRESH,
                Instant.parse("2026-07-22T00:02:00Z"), "REFERENCE_VALUATION_V1", List.of());
    }
}
