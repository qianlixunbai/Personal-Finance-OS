package com.financeos.module.ai.tool;

import com.financeos.module.ai.tool.dto.MonthlyCashFlowFact;
import com.financeos.module.ledger.dto.MonthlyCashFlowAggregate;
import com.financeos.module.ledger.service.TransactionQueryService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MonthlyCashFlowTool {

    private static final String BASE_CURRENCY = "CNY";
    private static final int MAX_MONTHS = 12;

    private final TransactionQueryService transactionQueryService;

    public MonthlyCashFlowTool(TransactionQueryService transactionQueryService) {
        this.transactionQueryService = transactionQueryService;
    }

    public MonthlyCashFlowFact getMonthlyCashFlow(
            Long authenticatedUserId, YearMonth fromMonth, YearMonth throughMonth) {
        validateAuthenticatedUserId(authenticatedUserId);
        validateMonthRange(fromMonth, throughMonth);

        LocalDateTime startInclusive;
        LocalDateTime endExclusive;
        try {
            startInclusive = fromMonth.atDay(1).atStartOfDay();
            endExclusive = throughMonth.plusMonths(1).atDay(1).atStartOfDay();
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Month range is outside the supported date range", exception);
        }

        List<MonthlyCashFlowAggregate> aggregates = transactionQueryService.monthlyCashFlowByMonth(
                authenticatedUserId, startInclusive, endExclusive);
        Map<YearMonth, MonthlyCashFlowAggregate> aggregatesByMonth = new HashMap<>();
        for (MonthlyCashFlowAggregate aggregate : aggregates) {
            if (aggregate != null && aggregate.monthStart() != null) {
                aggregatesByMonth.put(YearMonth.from(aggregate.monthStart()), aggregate);
            }
        }

        int monthCount = (int) ChronoUnit.MONTHS.between(fromMonth, throughMonth) + 1;
        List<MonthlyCashFlowFact.MonthCashFlow> months = new ArrayList<>(monthCount);
        for (int offset = 0; offset < monthCount; offset++) {
            YearMonth month = fromMonth.plusMonths(offset);
            MonthlyCashFlowAggregate aggregate = aggregatesByMonth.get(month);
            BigDecimal income = aggregate == null || aggregate.income() == null
                    ? BigDecimal.ZERO
                    : aggregate.income();
            BigDecimal expense = aggregate == null || aggregate.expense() == null
                    ? BigDecimal.ZERO
                    : aggregate.expense();

            months.add(new MonthlyCashFlowFact.MonthCashFlow(
                    month.toString(), income, expense, income.subtract(expense)));
        }

        return new MonthlyCashFlowFact(
                BASE_CURRENCY, fromMonth.toString(), throughMonth.toString(), months);
    }

    private void validateAuthenticatedUserId(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("authenticatedUserId must be a positive number");
        }
    }

    private void validateMonthRange(YearMonth fromMonth, YearMonth throughMonth) {
        if (fromMonth == null || throughMonth == null) {
            throw new IllegalArgumentException("fromMonth and throughMonth are required");
        }
        if (throughMonth.isBefore(fromMonth)) {
            throw new IllegalArgumentException("throughMonth must not be before fromMonth");
        }
        if (ChronoUnit.MONTHS.between(fromMonth, throughMonth) >= MAX_MONTHS) {
            throw new IllegalArgumentException("Month range must not exceed 12 months");
        }
    }
}
