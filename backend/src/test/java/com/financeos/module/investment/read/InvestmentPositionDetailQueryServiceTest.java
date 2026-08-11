package com.financeos.module.investment.read;

import com.financeos.common.BusinessException;
import com.financeos.module.asset.entity.Asset;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import com.financeos.module.investment.read.dto.InvestmentPositionDetail;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadRow;
import com.financeos.module.investment.read.service.InvestmentPositionDetailQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentPositionDetailQueryServiceTest {

    @Test
    void readsAnOwnedTransactionDrivenProjectionWithoutExposingInternalFields() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        InvestmentPositionDetailQueryService service = new InvestmentPositionDetailQueryService(mapper,
                mock(MarketQuoteQueryService.class), mock(ReferenceValuationService.class));
        InvestmentReadRow row = new InvestmentReadRow();
        row.setPositionId(11L);
        row.setPositionMode("TRANSACTION_DRIVEN");
        row.setAccountId(3L);
        row.setAccountName("Brokerage");
        row.setInstrumentId(4L);
        row.setInstrumentSymbol("AAPL");
        row.setInstrumentName("Apple");
        row.setInstrumentMarket("US");
        row.setInstrumentAssetClass("STOCK");
        row.setInstrumentQuoteCurrency("USD");
        row.setQuantity(new BigDecimal("2.00000000"));
        row.setAverageCost(new BigDecimal("10.00000000"));
        row.setTotalCost(new BigDecimal("20.00"));
        row.setCumulativeRealizedProfitLoss(new BigDecimal("3.00"));
        row.setPositionStatus("OPEN");
        Asset asset = new Asset();
        asset.setId(11L);
        asset.setCurrentPrice(new BigDecimal("11.00000000"));
        asset.setMarketValue(new BigDecimal("22.00"));
        when(mapper.selectPositionDetail(7L, 11L)).thenReturn(row);
        when(mapper.selectOwnedTransactionDrivenPositionAsset(7L, 11L)).thenReturn(asset);

        InvestmentPositionDetail response = service.get(7L, 11L);

        assertThat(response.positionId()).isEqualTo(11L);
        assertThat(response.positionMode()).isEqualTo("TRANSACTION_DRIVEN");
        assertThat(response.account().displayName()).isEqualTo("Brokerage");
        assertThat(response.quantity()).isEqualTo("2.00000000");
        assertThat(response.manualReference().currentPrice()).isEqualTo("11.00000000");
        assertThat(response.referenceValuation().accountingTruth()).isFalse();
    }

    @Test
    void mapsMissingOrForeignAndLegacyPositionsToOneNotFoundBoundary() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        InvestmentPositionDetailQueryService service = new InvestmentPositionDetailQueryService(mapper,
                mock(MarketQuoteQueryService.class), mock(ReferenceValuationService.class));

        assertThatThrownBy(() -> service.get(7L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(404);
    }
}
