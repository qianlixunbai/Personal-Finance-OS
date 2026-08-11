package com.financeos.module.investment.read;

import com.financeos.module.investment.read.dto.CursorPage;
import com.financeos.module.investment.read.dto.InvestmentLogicalTransactionListItem;
import com.financeos.module.investment.read.dto.InvestmentPositionListItem;
import com.financeos.module.investment.read.dto.InvestmentTransactionAuditTimeline;
import com.financeos.module.investment.entity.InvestmentTransaction;
import com.financeos.module.investment.entity.InvestmentTransactionCorrection;
import com.financeos.module.investment.mapper.InvestmentTransactionCorrectionMapper;
import com.financeos.module.investment.mapper.InvestmentTransactionMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadRow;
import com.financeos.module.investment.read.mapper.LogicalTransactionReadCriteria;
import com.financeos.module.investment.read.mapper.PositionReadCriteria;
import com.financeos.module.investment.read.model.LogicalTransactionListQuery;
import com.financeos.module.investment.read.model.PositionListQuery;
import com.financeos.module.investment.read.service.InvestmentLogicalTransactionReadQueryService;
import com.financeos.module.investment.read.service.InvestmentLogicalTransactionDetailQueryService;
import com.financeos.module.investment.read.service.InvestmentPositionReadQueryService;
import com.financeos.module.asset.marketdata.service.MarketQuoteQueryService;
import com.financeos.module.asset.valuation.service.ReferenceValuationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
        assertThat(page.records().getFirst().referenceValuation().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(page.nextCursor()).isNotBlank();
        verify(mapper, times(1)).ownedAccount(7L, 8L);
        verify(mapper, times(1)).selectPositions(any(PositionReadCriteria.class));
    }

    @Test
    void oneHundredPositionsUseOnePositionPageOneQuoteBatchAndOneValuationBatch() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        MarketQuoteQueryService quoteQueries = mock(MarketQuoteQueryService.class);
        ReferenceValuationService valuationService = mock(ReferenceValuationService.class);
        InvestmentPositionReadQueryService service = new InvestmentPositionReadQueryService(mapper, quoteQueries, valuationService);
        List<InvestmentReadRow> rows = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(this::row).toList();
        rows.forEach(row -> {
            row.setInstrumentSymbol("S" + row.getPositionId());
            row.setInstrumentMarket("US");
        });
        when(mapper.selectPositions(any(PositionReadCriteria.class))).thenReturn(rows);
        when(quoteQueries.findCachedUsQuotes(any())).thenReturn(java.util.Map.of());
        when(valuationService.calculateAll(any(), any())).thenReturn(java.util.Map.of());

        CursorPage<InvestmentPositionListItem> page = service.list(7L,
                new PositionListQuery("OPEN", null, null, null, 100));

        assertThat(page.records()).hasSize(100);
        verify(mapper, times(1)).selectPositions(any(PositionReadCriteria.class));
        verify(quoteQueries, times(1)).findCachedUsQuotes(any());
        verify(valuationService, times(1)).calculateAll(any(), any());
    }

    @Test
    void oneHundredLogicalTransactionsUseOneFoldedListQueryWithoutPerRowReads() {
        InvestmentReadMapper mapper = mock(InvestmentReadMapper.class);
        InvestmentLogicalTransactionReadQueryService service = new InvestmentLogicalTransactionReadQueryService(mapper);
        List<InvestmentReadRow> rows = java.util.stream.LongStream.rangeClosed(1, 100)
                .mapToObj(this::logicalRow).toList();
        when(mapper.selectLogicalTransactions(any(LogicalTransactionReadCriteria.class))).thenReturn(rows);

        CursorPage<InvestmentLogicalTransactionListItem> page = service.list(7L,
                new LogicalTransactionListQuery(null, null, null, null, null, null, null, null, 100));

        assertThat(page.records()).hasSize(100);
        verify(mapper, times(1)).selectLogicalTransactions(any(LogicalTransactionReadCriteria.class));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void replacementTimelineUsesAConstantSetOfRelationQueries() {
        InvestmentTransactionMapper transactionMapper = mock(InvestmentTransactionMapper.class);
        InvestmentTransactionCorrectionMapper correctionMapper = mock(InvestmentTransactionCorrectionMapper.class);
        InvestmentReadMapper readMapper = mock(InvestmentReadMapper.class);
        InvestmentLogicalTransactionDetailQueryService service = new InvestmentLogicalTransactionDetailQueryService(
                transactionMapper, correctionMapper, readMapper);
        UUID groupId = UUID.randomUUID();
        InvestmentTransaction original = fact(1L, "BUY", null, null, null);
        InvestmentTransaction reversal = fact(2L, "REVERSAL", groupId, 1L, null);
        InvestmentTransaction replacement = fact(3L, "BUY", groupId, null, 1L);
        InvestmentTransactionCorrection correction = correction(groupId);
        InvestmentReadRow position = row(11L);
        when(transactionMapper.findByUserIdAndId(7L, 1L)).thenReturn(original);
        when(readMapper.selectPositionDetail(7L, 11L)).thenReturn(position);
        when(transactionMapper.findReversalByUserIdAndOriginalTransactionId(7L, 1L)).thenReturn(reversal);
        when(correctionMapper.findByUserIdAndOriginalTransactionId(7L, 1L)).thenReturn(correction);
        when(transactionMapper.findByUserIdAndId(7L, 3L)).thenReturn(replacement);

        InvestmentTransactionAuditTimeline timeline = service.auditTimeline(7L, 1L);

        assertThat(timeline.events()).extracting(InvestmentTransactionAuditTimeline.AuditEvent::eventKind)
                .containsExactly("ORIGINAL_POSTING", "REPLACEMENT_COMMAND");
        verify(transactionMapper, times(1)).findByUserIdAndId(7L, 1L);
        verify(readMapper, times(1)).selectPositionDetail(7L, 11L);
        verify(transactionMapper, times(1)).findReversalByUserIdAndOriginalTransactionId(7L, 1L);
        verify(correctionMapper, times(1)).findByUserIdAndOriginalTransactionId(7L, 1L);
        verify(transactionMapper, times(1)).findByUserIdAndId(7L, 3L);
        verifyNoMoreInteractions(transactionMapper, correctionMapper, readMapper);
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

    private InvestmentReadRow logicalRow(long logicalTransactionId) {
        InvestmentReadRow row = row(logicalTransactionId);
        row.setLogicalTransactionId(logicalTransactionId);
        row.setTransactionType("BUY");
        row.setEffectiveTradeTime(Instant.parse("2026-08-01T10:00:00Z").plusSeconds(logicalTransactionId));
        row.setUnitPrice(new BigDecimal("10.00000000"));
        row.setGrossAmount(new BigDecimal("10.00"));
        row.setFeeAmount(new BigDecimal("0.00"));
        row.setTaxAmount(new BigDecimal("0.00"));
        row.setNetAmount(new BigDecimal("10.00"));
        row.setReleasedCostAmount(new BigDecimal("0.00"));
        row.setRealizedProfitLoss(new BigDecimal("0.00"));
        row.setCorrectionStatus("UNCHANGED");
        row.setEffective(true);
        return row;
    }

    private InvestmentTransaction fact(Long id, String type, UUID groupId, Long originalId, Long replayAnchorId) {
        InvestmentTransaction fact = new InvestmentTransaction();
        fact.setId(id);
        fact.setUserId(7L);
        fact.setAssetId(11L);
        fact.setAccountId(8L);
        fact.setTransactionType(type);
        fact.setQuantity(new BigDecimal("1.00000000"));
        fact.setUnitPrice(new BigDecimal("10.00000000"));
        fact.setGrossAmount(new BigDecimal("10.00"));
        fact.setFeeAmount(new BigDecimal("0.00"));
        fact.setTaxAmount(new BigDecimal("0.00"));
        fact.setNetAmount(new BigDecimal("10.00"));
        fact.setReleasedCostAmount(new BigDecimal("0.00"));
        fact.setRealizedProfitLoss(new BigDecimal("0.00"));
        fact.setTradeTime(Instant.parse("2026-08-01T10:00:00Z"));
        fact.setSettlementTime(fact.getTradeTime());
        fact.setCreatedAt(fact.getTradeTime());
        fact.setCorrectionGroupId(groupId);
        fact.setOriginalTransactionId(originalId);
        fact.setReplayAnchorTransactionId(replayAnchorId);
        fact.setAccountBalanceAfter(new BigDecimal("0.00"));
        fact.setPositionQuantityAfter(new BigDecimal("1.00000000"));
        fact.setPositionAvgCostAfter(new BigDecimal("10.00000000"));
        fact.setPositionTotalCostAfter(new BigDecimal("10.00"));
        fact.setPositionRealizedProfitLossAfter(new BigDecimal("0.00"));
        fact.setPositionStatusAfter("OPEN");
        return fact;
    }

    private InvestmentTransactionCorrection correction(UUID groupId) {
        InvestmentTransactionCorrection correction = new InvestmentTransactionCorrection();
        correction.setCorrectionGroupId(groupId);
        correction.setUserId(7L);
        correction.setAccountId(8L);
        correction.setAssetId(11L);
        correction.setOriginalTransactionId(1L);
        correction.setTransactionType("BUY");
        correction.setReversalTransactionId(2L);
        correction.setReplacementTransactionId(3L);
        correction.setBalanceAfter(new BigDecimal("0.00"));
        correction.setPositionQuantityAfter(new BigDecimal("1.00000000"));
        correction.setPositionAvgCostAfter(new BigDecimal("10.00000000"));
        correction.setPositionTotalCostAfter(new BigDecimal("10.00"));
        correction.setPositionRealizedProfitLossAfter(new BigDecimal("0.00"));
        correction.setPositionStatusAfter("OPEN");
        correction.setCreatedAt(Instant.parse("2026-08-02T10:00:00Z"));
        return correction;
    }
}
