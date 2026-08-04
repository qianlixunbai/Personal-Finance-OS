package com.financeos.module.investment.read;

import com.financeos.module.investment.read.dto.CursorPage;
import com.financeos.module.investment.read.dto.InvestmentPositionListItem;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadRow;
import com.financeos.module.investment.read.mapper.PositionReadCriteria;
import com.financeos.module.investment.read.model.PositionListQuery;
import com.financeos.module.investment.read.service.InvestmentPositionReadQueryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentReadQueryServiceTest {
    @Test
    void truncatesTheSizePlusOneRowAndUsesOnlyOneListMapperCall() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        InvestmentPositionReadQueryService service = new InvestmentPositionReadQueryService(mapper);
        when(mapper.ownedAccount(7L, 8L)).thenReturn(true);
        when(mapper.selectPositions(any(PositionReadCriteria.class))).thenReturn(List.of(row(1L), row(2L), row(3L)));

        CursorPage<InvestmentPositionListItem> page = service.list(7L, new PositionListQuery(null, 8L, null, null, 2));

        assertThat(page.records()).extracting(InvestmentPositionListItem::positionId).containsExactly(1L, 2L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        verify(mapper, times(1)).ownedAccount(7L, 8L);
        verify(mapper, times(1)).selectPositions(any(PositionReadCriteria.class));
    }

    private InvestmentReadRow row(Long positionId) {
        InvestmentReadRow row = new InvestmentReadRow();
        row.setPositionId(positionId);
        row.setInstrumentId(positionId);
        row.setAccountId(8L);
        row.setPositionMode("TRANSACTION_DRIVEN");
        row.setPositionStatus("OPEN");
        row.setQuantity(new BigDecimal("1.00000000"));
        row.setAverageCost(new BigDecimal("10.00000000"));
        row.setTotalCost(new BigDecimal("10.00"));
        row.setCumulativeRealizedProfitLoss(BigDecimal.ZERO.setScale(2));
        return row;
    }
}
