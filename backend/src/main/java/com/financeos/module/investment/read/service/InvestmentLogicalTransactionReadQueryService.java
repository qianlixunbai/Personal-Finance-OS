package com.financeos.module.investment.read.service;

import com.financeos.common.BusinessException;
import com.financeos.module.investment.read.cursor.CursorCodec;
import com.financeos.module.investment.read.cursor.LogicalTransactionCursor;
import com.financeos.module.investment.read.dto.CursorPage;
import com.financeos.module.investment.read.dto.InvestmentLogicalTransactionListItem;
import com.financeos.module.investment.read.dto.InvestmentPositionListItem;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadRow;
import com.financeos.module.investment.read.mapper.LogicalTransactionReadCriteria;
import com.financeos.module.investment.read.model.LogicalTransactionListQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class InvestmentLogicalTransactionReadQueryService {
    private final InvestmentReadMapper mapper;

    public InvestmentLogicalTransactionReadQueryService(InvestmentReadMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CursorPage<InvestmentLogicalTransactionListItem> list(Long userId, LogicalTransactionListQuery query) {
        LogicalTransactionListQuery value = query == null ? new LogicalTransactionListQuery(null, null, null, null, null, null, null, null, null) : query;
        Long positionId = InvestmentReadSupport.positive("positionId", value.positionId());
        Long accountId = InvestmentReadSupport.positive("accountId", value.accountId());
        Long instrumentId = InvestmentReadSupport.positive("instrumentId", value.instrumentId());
        String type = InvestmentReadSupport.enumValue("type", value.type(), "OPENING_POSITION", "BUY", "SELL", "DIVIDEND");
        String correctionStatus = InvestmentReadSupport.enumValue("correctionStatus", value.correctionStatus(), "UNCHANGED", "REVERSED", "REPLACED");
        validateRange(value.from(), value.to());
        int size = InvestmentReadSupport.pageSize(value.size());
        if (positionId != null) InvestmentReadSupport.notFoundIf(mapper.ownedPosition(userId, positionId));
        if (accountId != null) InvestmentReadSupport.notFoundIf(mapper.ownedAccount(userId, accountId));
        if (instrumentId != null) InvestmentReadSupport.notFoundIf(mapper.ownedInstrument(userId, instrumentId));
        String fingerprint = InvestmentReadSupport.fingerprint("logical|position=" + positionId + "|account=" + accountId
                + "|instrument=" + instrumentId + "|type=" + type + "|correctionStatus=" + correctionStatus
                + "|from=" + value.from() + "|to=" + value.to());
        LogicalTransactionCursor cursor = InvestmentReadSupport.supplied(value.cursor())
                ? CursorCodec.decodeLogicalTransaction(value.cursor(), fingerprint) : null;
        List<InvestmentReadRow> rows = mapper.selectLogicalTransactions(new LogicalTransactionReadCriteria(userId, positionId,
                accountId, instrumentId, type, correctionStatus, value.from(), value.to(),
                cursor == null ? null : cursor.effectiveTradeTime(), cursor == null ? null : cursor.logicalTransactionId(), size + 1));
        boolean hasMore = rows.size() > size;
        List<InvestmentReadRow> visible = new ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        List<InvestmentLogicalTransactionListItem> records = visible.stream().map(this::map).toList();
        String next = hasMore ? CursorCodec.encodeLogicalTransaction(cursorOf(visible.getLast()), fingerprint) : null;
        return new CursorPage<>(records, next, hasMore, size);
    }

    private void validateRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(400, "from must be before to");
        }
    }

    private LogicalTransactionCursor cursorOf(InvestmentReadRow row) {
        return new LogicalTransactionCursor(row.getEffectiveTradeTime(), row.getLogicalTransactionId());
    }

    private InvestmentLogicalTransactionListItem map(InvestmentReadRow row) {
        InvestmentPositionListItem.Account account = new InvestmentPositionListItem.Account(row.getAccountId(), row.getAccountName());
        InvestmentPositionListItem.Instrument instrument = new InvestmentPositionListItem.Instrument(row.getInstrumentId(),
                row.getInstrumentSymbol(), row.getInstrumentName(), row.getInstrumentMarket(), row.getInstrumentAssetClass(),
                row.getInstrumentQuoteCurrency());
        return new InvestmentLogicalTransactionListItem(row.getLogicalTransactionId(), row.getPositionId(), account, instrument,
                row.getTransactionType(), row.getEffectiveTradeTime(), InvestmentReadSupport.decimal(row.getQuantity(), 8),
                InvestmentReadSupport.decimal(row.getUnitPrice(), 8), InvestmentReadSupport.decimal(row.getGrossAmount(), 2),
                InvestmentReadSupport.decimal(row.getFeeAmount(), 2), InvestmentReadSupport.decimal(row.getTaxAmount(), 2),
                InvestmentReadSupport.decimal(row.getNetAmount(), 2), InvestmentReadSupport.decimal(row.getReleasedCostAmount(), 2),
                InvestmentReadSupport.decimal(row.getRealizedProfitLoss(), 2), row.getCorrectionStatus(), Boolean.TRUE.equals(row.getEffective()),
                row.getCorrectionCreatedAt());
    }
}
