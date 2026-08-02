package com.financeos.module.investment.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

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
    void rejectsDividendWithoutAnEarlierEffectiveBuyOrOpeningPosition() {
        assertThatThrownBy(() -> replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.dividend(decimal("10.00"), decimal("0.00"), decimal("0.00"))))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Dividend requires an earlier effective buy or opening position");
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

    @Test
    void rejectsOpeningAfterAFullSellInsteadOfResettingThePosition() {
        assertThatThrownBy(() -> replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(3, "2026-01-03T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Opening position must be the first effective investment transaction");
    }

    @Test
    void rejectsOpeningAfterBuyDividendOrAnotherOpening() {
        assertThatThrownBy(() -> replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Opening position must be the first effective investment transaction");
        assertThatThrownBy(() -> replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.dividend(decimal("1.00"), decimal("0.00"), decimal("0.00"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Dividend requires an earlier effective buy or opening position");
        assertThatThrownBy(() -> replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Opening position must be the first effective investment transaction");
    }

    @Test
    void allowsOpeningWhenEarlierOpeningWasReversedAndReturnsEmptyForAllReversedEntries() {
        InvestmentReplayResult result = replayEngine.replay(List.of(
                entry(1, "2026-01-01T09:00:00", InvestmentTransactionStatus.REVERSED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))),
                entry(2, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("10.00000000"))),
                entry(3, "2026-01-03T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")))));

        assertThat(result.position().quantity()).isEqualByComparingTo("2.00000000");
        assertThat(replayEngine.replay(List.of(
                entry(4, "2026-01-04T09:00:00", InvestmentTransactionStatus.REVERSED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))))))
                .isEqualTo(new InvestmentReplayResult(InvestmentPositionState.empty(), List.of()));
    }

    @Test
    void replaysTheSameInstantsIndependentlyOfJvmDefaultTimeZone() {
        List<InvestmentReplayEntry> entries = List.of(
                entry(2, "2026-01-01T00:00:00Z", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("11.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(1, "2026-01-01T00:00:00Z", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))));
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            InvestmentReplayResult shanghaiResult = replayEngine.replay(entries);
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            InvestmentReplayResult losAngelesResult = replayEngine.replay(entries);

            assertThat(losAngelesResult).isEqualTo(shanghaiResult);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void replaysReplacementInItsOriginalLogicalSlotInsteadOfItsNewPhysicalIdSlot() {
        Instant tradeTime = Instant.parse("2026-01-01T09:00:00Z");
        InvestmentReplayResult result = replayEngine.replay(List.of(
                replacementEntry(999, tradeTime, InvestmentLedgerCommand.buy(
                        decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")), 100),
                entry(101, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("20.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(102, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("2.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(100, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("99.00000000"), decimal("0.00"), decimal("0.00"))),
                new InvestmentReplayEntry(1000, tradeTime, InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.reversal(), 100L)));

        assertThat(result.trace().steps())
                .extracting(InvestmentReplayTraceStep::logicalFactIdentity)
                .containsExactly(100L, 101L, 102L);
        assertThat(result.trace().steps())
                .extracting(InvestmentReplayTraceStep::effectiveAnchorId)
                .containsExactly(100L, 101L, 102L);
        assertThat(result.position().cumulativeRealizedProfitLoss()).isEqualByComparingTo("0.00");
    }

    @Test
    void givesEquivalentReplacementHistoriesTheSameDigestDespiteDifferentPhysicalReplacementIds() {
        Instant tradeTime = Instant.parse("2026-01-01T09:00:00Z");
        InvestmentReplayResult first = replayEngine.replay(List.of(
                replacementEntry(999, tradeTime, InvestmentLedgerCommand.buy(
                        decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")), 100),
                entry(101, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("11.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(100, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                new InvestmentReplayEntry(1000, tradeTime, InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.reversal(), 100L)));
        InvestmentReplayResult second = replayEngine.replay(List.of(
                replacementEntry(1999, tradeTime, InvestmentLedgerCommand.buy(
                        decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")), 100),
                entry(101, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("11.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(100, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                new InvestmentReplayEntry(2000, tradeTime, InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.reversal(), 100L)));

        assertThat(second.position()).isEqualTo(first.position());
        assertThat(second.trace().canonicalDigest()).isEqualTo(first.trace().canonicalDigest());
    }

    @Test
    void keepsThePostedSellFactUntouchedWhileReplacementReplayProducesCorrectedCurrentTruth() {
        Instant tradeTime = Instant.parse("2026-01-01T09:00:00Z");
        InvestmentReplayEntry postedSell = entry(101, "2026-01-02T09:00:00", InvestmentTransactionStatus.POSTED,
                InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00")));
        InvestmentReplayResult postedHistory = replayEngine.replay(List.of(
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                postedSell));

        InvestmentReplayResult correctedHistory = replayEngine.replay(List.of(
                replacementEntry(999, Instant.parse("2026-02-01T09:00:00Z"), InvestmentLedgerCommand.buy(
                        decimal("1.00000000"), decimal("20.00000000"), decimal("0.00"), decimal("0.00")), 100),
                postedSell,
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                new InvestmentReplayEntry(1000, tradeTime, InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.reversal(), 100L)));

        // The existing SELL fact is re-used verbatim: its posting-time row is never mutated.
        assertThat(postedSell.command().unitPrice()).isEqualByComparingTo("15.00000000");
        assertThat(postedHistory.appliedCalculations().getLast().releasedCostAmount()).isEqualByComparingTo("10.00");
        assertThat(correctedHistory.trace().steps().getLast().calculation().releasedCostAmount()).isEqualByComparingTo("20.00");
        assertThat(correctedHistory.trace().canonicalDigest()).isNotEqualTo(postedHistory.trace().canonicalDigest());
    }

    @Test
    void givesDifferentDigestsWhenEqualFinalPositionsWereReachedInDifferentIntermediateOrders() {
        Instant tradeTime = Instant.parse("2026-01-01T09:00:00Z");
        InvestmentReplayResult first = replayEngine.replay(List.of(
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(101, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("20.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(102, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00")))));
        InvestmentReplayResult second = replayEngine.replay(List.of(
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("20.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(101, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(102, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00")))));

        assertThat(second.position()).isEqualTo(first.position());
        assertThat(second.trace().canonicalDigest()).isNotEqualTo(first.trace().canonicalDigest());
    }

    @Test
    void givesDifferentDigestsWhenASellReleasedCostDiffers() {
        Instant tradeTime = Instant.parse("2026-01-01T09:00:00Z");
        InvestmentReplayResult first = replayEngine.replay(List.of(
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(101, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00")))));
        InvestmentReplayResult second = replayEngine.replay(List.of(
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("12.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(101, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00")))));

        assertThat(second.appliedCalculations().getLast().releasedCostAmount())
                .isNotEqualByComparingTo(first.appliedCalculations().getLast().releasedCostAmount());
        assertThat(second.trace().canonicalDigest()).isNotEqualTo(first.trace().canonicalDigest());
    }

    @Test
    void givesDifferentDigestsWhenASellRealizedProfitLossDiffers() {
        Instant tradeTime = Instant.parse("2026-01-01T09:00:00Z");
        InvestmentReplayResult first = replayEngine.replay(List.of(
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(101, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("15.00000000"), decimal("0.00"), decimal("0.00")))));
        InvestmentReplayResult second = replayEngine.replay(List.of(
                entry(100, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(101, tradeTime.toString(), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("16.00000000"), decimal("0.00"), decimal("0.00")))));

        assertThat(second.appliedCalculations().getLast().realizedProfitLoss())
                .isNotEqualByComparingTo(first.appliedCalculations().getLast().realizedProfitLoss());
        assertThat(second.trace().canonicalDigest()).isNotEqualTo(first.trace().canonicalDigest());
    }

    @Test
    void usesTheOriginalAnchorTradeTimeForReplacementOrdering() {
        Instant originalTradeTime = Instant.parse("2026-01-01T09:00:00Z");
        InvestmentReplayResult result = replayEngine.replay(List.of(
                replacementEntry(999, Instant.parse("2026-02-01T09:00:00Z"), InvestmentLedgerCommand.buy(
                        decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")), 100),
                entry(101, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("20.00000000"), decimal("0.00"), decimal("0.00"))),
                entry(100, "2026-01-01T09:00:00", InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("99.00000000"), decimal("0.00"), decimal("0.00"))),
                new InvestmentReplayEntry(1000, originalTradeTime, InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.reversal(), 100L)));

        assertThat(result.trace().steps())
                .extracting(InvestmentReplayTraceStep::logicalFactIdentity)
                .containsExactly(100L, 101L);
        assertThat(result.trace().steps().getFirst().tradeTime()).isEqualTo(originalTradeTime);
    }

    @Test
    void rejectsReplacementAnchorThatDoesNotReferToAnOriginalTradeFact() {
        assertThatThrownBy(() -> replayEngine.replay(List.of(
                replacementEntry(999, Instant.parse("2026-02-01T09:00:00Z"), InvestmentLedgerCommand.buy(
                        decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")), 100),
                new InvestmentReplayEntry(100, Instant.parse("2026-01-01T09:00:00Z"), InvestmentTransactionStatus.POSTED,
                        InvestmentLedgerCommand.reversal(), 50L))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessageContaining("Replacement replay anchor must reference a BUY, SELL or DIVIDEND fact");
    }

    @Test
    void rejectsAnyReplacementAnchorSequenceOtherThanOne() {
        Instant tradeTime = Instant.parse("2026-01-01T09:00:00Z");

        assertThatThrownBy(() -> new InvestmentReplayEntry(999, tradeTime, InvestmentTransactionStatus.POSTED,
                InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")),
                null, 100L, (short) 0))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessageContaining("anchor requires replay sequence 1");
        assertThatThrownBy(() -> new InvestmentReplayEntry(999, tradeTime, InvestmentTransactionStatus.POSTED,
                InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")),
                null, 100L, (short) 2))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessageContaining("anchor requires replay sequence 1");
    }

    private InvestmentReplayEntry entry(long id, String tradeTime, InvestmentTransactionStatus status, InvestmentLedgerCommand command) {
        return new InvestmentReplayEntry(id, Instant.parse(tradeTime.endsWith("Z") ? tradeTime : tradeTime + "Z"), status, command);
    }

    private InvestmentReplayEntry replacementEntry(long id, Instant tradeTime, InvestmentLedgerCommand command, long originalId) {
        return new InvestmentReplayEntry(id, tradeTime, InvestmentTransactionStatus.POSTED, command, null, originalId, (short) 1);
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private InvestmentPositionState stateOf(InvestmentCalculationResult result) {
        return new InvestmentPositionState(
                result.newQuantity(), result.newTotalCost(), result.newCumulativeRealizedProfitLoss());
    }
}
