package com.financeos.module.investment.instrument;

import com.financeos.common.BusinessException;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.mapper.InvestmentInstrumentMapper;
import com.financeos.module.investment.instrument.service.CreateInvestmentInstrumentCommand;
import com.financeos.module.investment.instrument.service.InvestmentInstrumentCommandService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentInstrumentCommandServiceTest {

    @Test
    void createsAnActiveCanonicalInstrumentForTheCurrentUser() {
        InvestmentInstrumentMapper mapper = mock(InvestmentInstrumentMapper.class);
        InvestmentInstrumentCommandService service = new InvestmentInstrumentCommandService(mapper);
        when(mapper.findByUserIdAndMarketAndSymbol(7L, "US", "AAPL")).thenReturn(null);

        InvestmentInstrument created = service.create(7L,
                new CreateInvestmentInstrumentCommand(" aapl ", " Apple Inc. ", " us ", "stock", " usd "));

        assertThat(created.getUserId()).isEqualTo(7L);
        assertThat(created.getSymbol()).isEqualTo("AAPL");
        assertThat(created.getName()).isEqualTo("Apple Inc.");
        assertThat(created.getMarket()).isEqualTo("US");
        assertThat(created.getAssetClass()).isEqualTo(InvestmentAssetClass.STOCK);
        assertThat(created.getQuoteCurrency()).isEqualTo("USD");
        assertThat(created.getStatus()).isEqualTo(InstrumentStatus.ACTIVE);
        verify(mapper).insert(created);
    }

    @Test
    void rejectsARepeatedInstrumentForTheSameUserAndMarket() {
        InvestmentInstrumentMapper mapper = mock(InvestmentInstrumentMapper.class);
        InvestmentInstrumentCommandService service = new InvestmentInstrumentCommandService(mapper);
        when(mapper.findByUserIdAndMarketAndSymbol(7L, "US", "AAPL"))
                .thenReturn(new InvestmentInstrument());

        assertThatThrownBy(() -> service.create(7L,
                new CreateInvestmentInstrumentCommand("AAPL", "Apple", "US", "STOCK", "USD")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(409);
        verify(mapper, never()).insert(any(InvestmentInstrument.class));
    }

    @Test
    void rejectsSymbolsOutsideTheCanonicalFormat() {
        InvestmentInstrumentMapper mapper = mock(InvestmentInstrumentMapper.class);
        InvestmentInstrumentCommandService service = new InvestmentInstrumentCommandService(mapper);

        assertThatThrownBy(() -> service.create(7L,
                new CreateInvestmentInstrumentCommand("BRK B", "Berkshire", "US", "STOCK", "USD")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(400);
        verify(mapper, never()).insert(any(InvestmentInstrument.class));
    }
}
