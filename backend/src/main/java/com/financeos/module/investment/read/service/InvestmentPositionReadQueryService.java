package com.financeos.module.investment.read.service;

import com.financeos.module.investment.read.cursor.CursorCodec;
import com.financeos.module.investment.read.cursor.PositionCursor;
import com.financeos.module.investment.read.dto.CursorPage;
import com.financeos.module.investment.read.dto.InvestmentPositionListItem;
import com.financeos.module.investment.read.mapper.InvestmentReadMapper;
import com.financeos.module.investment.read.mapper.InvestmentReadRow;
import com.financeos.module.investment.read.mapper.PositionReadCriteria;
import com.financeos.module.investment.read.model.PositionListQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class InvestmentPositionReadQueryService {
    private final InvestmentReadMapper mapper;

    public InvestmentPositionReadQueryService(InvestmentReadMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CursorPage<InvestmentPositionListItem> list(Long userId, PositionListQuery query) {
        String status = InvestmentReadSupport.enumValue("status", query == null ? null : query.status(), "OPEN", "CLOSED", "ALL");
        status = status == null ? "OPEN" : status;
        Long accountId = InvestmentReadSupport.positive("accountId", query == null ? null : query.accountId());
        Long instrumentId = InvestmentReadSupport.positive("instrumentId", query == null ? null : query.instrumentId());
        int size = InvestmentReadSupport.pageSize(query == null ? null : query.size());
        if (accountId != null) InvestmentReadSupport.notFoundIf(mapper.ownedAccount(userId, accountId));
        if (instrumentId != null) InvestmentReadSupport.notFoundIf(mapper.ownedInstrument(userId, instrumentId));
        String fingerprint = InvestmentReadSupport.fingerprint("position|status=" + status + "|account=" + accountId + "|instrument=" + instrumentId);
        PositionCursor cursor = InvestmentReadSupport.supplied(query == null ? null : query.cursor())
                ? CursorCodec.decodePosition(query.cursor(), fingerprint) : null;
        List<InvestmentReadRow> rows = mapper.selectPositions(new PositionReadCriteria(userId, status, accountId, instrumentId,
                cursor == null ? null : cursor.instrumentId(), cursor == null ? null : cursor.accountId(),
                cursor == null ? null : cursor.positionId(), size + 1));
        boolean hasMore = rows.size() > size;
        List<InvestmentReadRow> visible = new ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        List<InvestmentPositionListItem> records = visible.stream().map(this::map).toList();
        String next = hasMore ? CursorCodec.encodePosition(cursorOf(visible.getLast()), fingerprint) : null;
        return new CursorPage<>(records, next, hasMore, size);
    }

    private PositionCursor cursorOf(InvestmentReadRow row) {
        return new PositionCursor(row.getInstrumentId(), row.getAccountId(), row.getPositionId());
    }

    private InvestmentPositionListItem map(InvestmentReadRow row) {
        return new InvestmentPositionListItem(row.getPositionId(), row.getPositionMode(),
                new InvestmentPositionListItem.Account(row.getAccountId(), row.getAccountName()),
                new InvestmentPositionListItem.Instrument(row.getInstrumentId(), row.getInstrumentSymbol(), row.getInstrumentName(),
                        row.getInstrumentMarket(), row.getInstrumentAssetClass(), row.getInstrumentQuoteCurrency()),
                InvestmentReadSupport.decimal(row.getQuantity(), 8), InvestmentReadSupport.decimal(row.getAverageCost(), 8),
                InvestmentReadSupport.decimal(row.getTotalCost(), 2), InvestmentReadSupport.decimal(row.getCumulativeRealizedProfitLoss(), 2),
                row.getPositionStatus());
    }
}
