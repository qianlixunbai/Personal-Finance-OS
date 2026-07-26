package com.financeos.module.investment.instrument;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.service.InvestmentInstrumentQueryService;
import com.financeos.module.investment.instrument.service.InvestmentPositionBindingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestmentPositionBindingServiceTest {

    @Test
    void preparesABrokerageStockBindingWithoutCreatingAnEmptyPosition() {
        InvestmentInstrumentQueryService instruments = mock(InvestmentInstrumentQueryService.class);
        AccountQueryService accounts = mock(AccountQueryService.class);
        InvestmentPositionBindingService service = new InvestmentPositionBindingService(instruments, accounts);
        InvestmentInstrument instrument = activeInstrument(3L, InvestmentAssetClass.STOCK);
        Account account = activeAccount(5L, "BROKERAGE");
        when(instruments.findByUserIdAndId(1L, 3L)).thenReturn(instrument);
        when(accounts.findAccessibleAccount(1L, 5L)).thenReturn(account);

        var binding = service.validate(1L, 5L, 3L);

        assertThat(binding.account()).isSameAs(account);
        assertThat(binding.instrument()).isSameAs(instrument);
    }

    @Test
    void hidesAnInstrumentOwnedByAnotherUserAsNotFound() {
        InvestmentInstrumentQueryService instruments = mock(InvestmentInstrumentQueryService.class);
        AccountQueryService accounts = mock(AccountQueryService.class);
        InvestmentPositionBindingService service = new InvestmentPositionBindingService(instruments, accounts);
        when(instruments.findByUserIdAndId(1L, 3L)).thenReturn(null);

        assertThatThrownBy(() -> service.validate(1L, 5L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(404);
    }

    @Test
    void rejectsAnInactiveInstrumentBeforeAnAccountCanBeBound() {
        InvestmentInstrumentQueryService instruments = mock(InvestmentInstrumentQueryService.class);
        AccountQueryService accounts = mock(AccountQueryService.class);
        InvestmentPositionBindingService service = new InvestmentPositionBindingService(instruments, accounts);
        InvestmentInstrument instrument = activeInstrument(3L, InvestmentAssetClass.STOCK);
        instrument.setStatus(InstrumentStatus.INACTIVE);
        when(instruments.findByUserIdAndId(1L, 3L)).thenReturn(instrument);
        when(accounts.findAccessibleAccount(1L, 5L)).thenReturn(activeAccount(5L, "BROKERAGE"));

        assertThatThrownBy(() -> service.validate(1L, 5L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(409);
    }

    @Test
    void failsClosedForUnsupportedAccountAndAssetClassCombinations() {
        InvestmentInstrumentQueryService instruments = mock(InvestmentInstrumentQueryService.class);
        AccountQueryService accounts = mock(AccountQueryService.class);
        InvestmentPositionBindingService service = new InvestmentPositionBindingService(instruments, accounts);
        when(instruments.findByUserIdAndId(1L, 3L)).thenReturn(activeInstrument(3L, InvestmentAssetClass.CRYPTO));
        when(accounts.findAccessibleAccount(1L, 5L)).thenReturn(activeAccount(5L, "BROKERAGE"));

        assertThatThrownBy(() -> service.validate(1L, 5L, 3L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getCode())
                .isEqualTo(400);
    }

    private InvestmentInstrument activeInstrument(Long id, InvestmentAssetClass assetClass) {
        InvestmentInstrument instrument = new InvestmentInstrument();
        instrument.setId(id);
        instrument.setAssetClass(assetClass);
        instrument.setStatus(InstrumentStatus.ACTIVE);
        return instrument;
    }

    private Account activeAccount(Long id, String type) {
        Account account = new Account();
        account.setId(id);
        account.setType(type);
        account.setStatus("ACTIVE");
        account.setCurrency("CNY");
        return account;
    }
}
