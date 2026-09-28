package com.financeos.module.ai.tool;

import com.financeos.module.ai.tool.dto.MonthlyCashFlowFact;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import com.financeos.module.ledger.service.TransactionQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MonthlyCashFlowToolTest {

    @Test
    void returnsClosedTwelveMonthRangeAndFillsMissingMonthsWithZero() {
        long authenticatedUserId = 73L;
        YearMonth fromMonth = YearMonth.of(2025, 1);
        YearMonth throughMonth = YearMonth.of(2025, 12);
        TransactionQueryService transactionQueryService = mock(TransactionQueryService.class);
        when(transactionQueryService.monthlyCashFlowByMonth(
                authenticatedUserId,
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0)))
                .thenReturn(List.of(
                        new MonthlyCashFlowAggregate(LocalDate.of(2025, 1, 1),
                                new BigDecimal("1000.00"), new BigDecimal("240.00")),
                        new MonthlyCashFlowAggregate(LocalDate.of(2025, 4, 1),
                                null, new BigDecimal("80.00")),
                        new MonthlyCashFlowAggregate(LocalDate.of(2025, 12, 1),
                                new BigDecimal("12.00"), new BigDecimal("5.00"))));

        MonthlyCashFlowFact result = new MonthlyCashFlowTool(transactionQueryService)
                .getMonthlyCashFlow(authenticatedUserId, fromMonth, throughMonth);

        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.fromMonth()).isEqualTo("2025-01");
        assertThat(result.throughMonth()).isEqualTo("2025-12");
        assertThat(result.months()).hasSize(12);
        assertThat(result.months().get(0).month()).isEqualTo("2025-01");
        assertThat(result.months().get(0).income()).isEqualByComparingTo("1000.00");
        assertThat(result.months().get(0).expense()).isEqualByComparingTo("240.00");
        assertThat(result.months().get(0).net()).isEqualByComparingTo("760.00");
        assertThat(result.months().get(1).month()).isEqualTo("2025-02");
        assertThat(result.months().get(1).income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.months().get(1).expense()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.months().get(1).net()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.months().get(3).income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.months().get(3).expense()).isEqualByComparingTo("80.00");
        assertThat(result.months().get(3).net()).isEqualByComparingTo("-80.00");
        assertThat(result.months().get(11).month()).isEqualTo("2025-12");
        assertThat(result.months().get(11).net()).isEqualByComparingTo("7.00");
        verify(transactionQueryService).monthlyCashFlowByMonth(
                authenticatedUserId,
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void rejectsReversedRangeBeforeQuerying() {
        TransactionQueryService transactionQueryService = mock(TransactionQueryService.class);

        assertThatThrownBy(() -> new MonthlyCashFlowTool(transactionQueryService)
                .getMonthlyCashFlow(73L, YearMonth.of(2025, 3), YearMonth.of(2025, 2)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionQueryService);
    }

    @Test
    void rejectsRangeLongerThanTwelveMonthsBeforeQuerying() {
        TransactionQueryService transactionQueryService = mock(TransactionQueryService.class);

        assertThatThrownBy(() -> new MonthlyCashFlowTool(transactionQueryService)
                .getMonthlyCashFlow(73L, YearMonth.of(2025, 1), YearMonth.of(2026, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionQueryService);
    }

    @Test
    void rejectsInvalidAuthenticatedUserBeforeQuerying() {
        TransactionQueryService transactionQueryService = mock(TransactionQueryService.class);

        assertThatThrownBy(() -> new MonthlyCashFlowTool(transactionQueryService)
                .getMonthlyCashFlow(0L, YearMonth.of(2025, 1), YearMonth.of(2025, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transactionQueryService);
    }
}
