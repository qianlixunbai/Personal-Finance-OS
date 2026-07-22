package com.financeos.module.investment.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentReplayEngineTest {

    private final InvestmentReplayEngine replayEngine = new InvestmentReplayEngine();

    @Test
    void replaysAnEmptyListAsAnEmptyPosition() {
        InvestmentReplayResult result = replayEngine.replay(List.of());

        assertThat(result.position()).isEqualTo(InvestmentPositionState.empty());
        assertThat(result.appliedCalculations()).isEmpty();
    }

    @Test
    void replaysOpeningPositionBuysPartialSellDividendAndFullSell() {
        InvestmentReplayResult result = replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("10.00000000"), decimal("10.00000000"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("10.00000000"), decimal("12.00000000"), decimal("1.00"), decimal("0.00"))),
                entry(3, "2026-01-03T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("5.00000000"), decimal("15.00000000"), decimal("1.00"), decimal("0.00"))),
                entry(4, "2026-01-04T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.dividend(decimal("5.00"), decimal("0.00"), decimal("1.00"))),
                entry(5, "2026-01-05T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("15.00000000"), decimal("11.00000000"), decimal("0.00"), decimal("0.00")))));

        assertThat(result.position().quantity()).isEqualByComparingTo("0.00000000");
        assertThat(result.position().totalCost()).isEqualByComparingTo("0.00");
        assertThat(result.position().cumulativeRealizedProfitLoss()).isEqualByComparingTo("18.00");
        assertThat(result.appliedCalculations()).hasSize(5);
    }

    @Test
    void sortsSameTradeTimeByIdBeforeReplaying() {
        InvestmentReplayResult result = replayEngine.replay(List.of(
                entry(2, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("11.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")))));

        assertThat(result.position().quantity()).isEqualByComparingTo("0.00000000");
        assertThat(result.position().cumulativeRealizedProfitLoss()).isEqualByComparingTo("1.00");
    }

    @Test
    void skipsReversedTransactions() {
        InvestmentReplayResult result = replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.REVERSED,
                        InvestmentLedgerCommand.buy(decimal("10.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")))));

        assertThat(result.position().quantity()).isEqualByComparingTo("1.00000000");
        assertThat(result.appliedCalculations()).hasSize(1);
    }

    @Test
    void failsTheEntireReplayWhenAnIntermediateTransactionOversells() {
        assertThatThrownBy(() -> replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("2.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))))))
                .isInstanceOf(InvestmentLedgerValidationException.class);
    }

    @Test
    void matchesTheSameStepByStepCalculatorProjection() {
        List<InvestmentReplayEntry> entries = List.of(
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("2.00000000"), decimal("12.00000000"), decimal("1.00"), decimal("0.00"))),
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))),
                entry(3, "2026-01-03T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00"))));

        InvestmentLedgerCalculator calculator = new InvestmentLedgerCalculator();
        InvestmentCalculationResult opening = calculator.calculate(InvestmentPositionState.empty(), entries.get(1).command());
        InvestmentCalculationResult buy = calculator.calculate(stateOf(opening), entries.get(0).command());
        InvestmentCalculationResult sell = calculator.calculate(stateOf(buy), entries.get(2).command());

        InvestmentReplayResult result = replayEngine.replay(entries);

        assertThat(result.position()).isEqualTo(stateOf(sell));
    }

    private InvestmentReplayEntry entry(long id, String tradeTime, InvestmentTransactionStatus status, InvestmentLedgerCommand command) {
        return new InvestmentReplayEntry(id, LocalDateTime.parse(tradeTime), status, command);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private InvestmentPositionState stateOf(InvestmentCalculationResult result) {
        return new InvestmentPositionState(
                result.newQuantity(), result.newTotalCost(), result.newCumulativeRealizedProfitLoss());
    }
}
