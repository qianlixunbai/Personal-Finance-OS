package com.financeos.module.investment.ledger;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentLedgerCalculatorTest {

    private final InvestmentLedgerCalculator calculator = new InvestmentLedgerCalculator();

    @Test
    void calculatesBuyCostCashDeltaAndWeightedAverageCost() {
        InvestmentCalculationResult result = calculator.calculate(
                InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("2.00000000"), decimal("10.00000000"), decimal("1.00"), decimal("0.50")));

        assertThat(result.grossAmount()).isEqualByComparingTo("20.00");
        assertThat(result.netAmount()).isEqualByComparingTo("21.50");
        assertThat(result.cashDelta()).isEqualByComparingTo("-21.50");
        assertThat(result.newQuantity()).isEqualByComparingTo("2.00000000");
        assertThat(result.newTotalCost()).isEqualByComparingTo("21.50");
        assertThat(result.newAvgCost()).isEqualByComparingTo("10.75000000");
        assertThat(result.releasedCostAmount()).isEqualByComparingTo("0.00");
        assertThat(result.realizedProfitLoss()).isEqualByComparingTo("0.00");
        assertThat(result.newCumulativeRealizedProfitLoss()).isEqualByComparingTo("0.00");
    }

    @Test
    void accumulatesCostAcrossMultipleBuys() {
        InvestmentCalculationResult result = calculator.calculate(
                state("2.00000000", "21.50", "0.00"),
                InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("11.00000000"), decimal("0.00"), decimal("0.00")));

        assertThat(result.newQuantity()).isEqualByComparingTo("3.00000000");
        assertThat(result.newTotalCost()).isEqualByComparingTo("32.50");
        assertThat(result.newAvgCost()).isEqualByComparingTo("10.83333333");
    }

    @Test
    void calculatesPartialSellUsingWeightedAverageReleasedCost() {
        InvestmentCalculationResult result = calculator.calculate(
                state("10.00000000", "100.00", "5.00"),
                InvestmentLedgerCommand.sell(decimal("3.00000000"), decimal("12.00000000"), decimal("1.00"), decimal("1.00")));

        assertThat(result.grossAmount()).isEqualByComparingTo("36.00");
        assertThat(result.netAmount()).isEqualByComparingTo("34.00");
        assertThat(result.cashDelta()).isEqualByComparingTo("34.00");
        assertThat(result.newQuantity()).isEqualByComparingTo("7.00000000");
        assertThat(result.newTotalCost()).isEqualByComparingTo("70.00");
        assertThat(result.newAvgCost()).isEqualByComparingTo("10.00000000");
        assertThat(result.releasedCostAmount()).isEqualByComparingTo("30.00");
        assertThat(result.realizedProfitLoss()).isEqualByComparingTo("4.00");
        assertThat(result.newCumulativeRealizedProfitLoss()).isEqualByComparingTo("9.00");
    }

    @Test
    void releasesAllRemainingCostOnFullSellWithoutRoundingResidue() {
        InvestmentCalculationResult result = calculator.calculate(
                state("3.00000000", "100.01", "0.00"),
                InvestmentLedgerCommand.sell(decimal("3.00000000"), decimal("40.00000000"), decimal("0.00"), decimal("0.00")));

        assertThat(result.newQuantity()).isEqualByComparingTo("0.00000000");
        assertThat(result.newTotalCost()).isEqualByComparingTo("0.00");
        assertThat(result.newAvgCost()).isEqualByComparingTo("0.00000000");
        assertThat(result.releasedCostAmount()).isEqualByComparingTo("100.01");
        assertThat(result.realizedProfitLoss()).isEqualByComparingTo("19.99");
    }

    @Test
    void calculatesZeroAndNegativeRealizedProfitLoss() {
        InvestmentCalculationResult breakEven = calculator.calculate(
                state("1.00000000", "10.00", "0.00"),
                InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("10.00000000"), decimal("0.00"), decimal("0.00")));
        InvestmentCalculationResult loss = calculator.calculate(
                state("1.00000000", "10.00", "0.00"),
                InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("9.00000000"), decimal("0.00"), decimal("0.00")));

        assertThat(breakEven.realizedProfitLoss()).isEqualByComparingTo("0.00");
        assertThat(loss.realizedProfitLoss()).isEqualByComparingTo("-1.00");
    }

    @Test
    void calculatesDividendWithoutChangingPositionOrCost() {
        InvestmentCalculationResult result = calculator.calculate(
                state("10.00000000", "100.00", "5.00"),
                InvestmentLedgerCommand.dividend(decimal("10.01"), decimal("0.50"), decimal("1.00")));

        assertThat(result.grossAmount()).isEqualByComparingTo("10.01");
        assertThat(result.netAmount()).isEqualByComparingTo("8.51");
        assertThat(result.cashDelta()).isEqualByComparingTo("8.51");
        assertThat(result.newQuantity()).isEqualByComparingTo("10.00000000");
        assertThat(result.newTotalCost()).isEqualByComparingTo("100.00");
        assertThat(result.newAvgCost()).isEqualByComparingTo("10.00000000");
        assertThat(result.realizedProfitLoss()).isEqualByComparingTo("0.00");
    }

    @Test
    void calculatesOpeningPositionWithHalfUpMoneyRoundingAndNoCashDelta() {
        InvestmentCalculationResult result = calculator.calculate(
                InvestmentPositionState.empty(),
                InvestmentLedgerCommand.openingPosition(decimal("1.00000000"), decimal("1.00500000")));

        assertThat(result.grossAmount()).isEqualByComparingTo("1.01");
        assertThat(result.netAmount()).isEqualByComparingTo("0.00");
        assertThat(result.cashDelta()).isEqualByComparingTo("0.00");
        assertThat(result.newTotalCost()).isEqualByComparingTo("1.01");
        assertThat(result.newAvgCost()).isEqualByComparingTo("1.01000000");
    }

    @Test
    void rejectsOversellAndFeesThatExceedSellGrossAmount() {
        assertThatThrownBy(() -> calculator.calculate(
                state("1.00000000", "10.00", "0.00"),
                InvestmentLedgerCommand.sell(decimal("1.00000001"), decimal("10.00000000"), decimal("0.00"), decimal("0.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(
                state("1.00000000", "10.00", "0.00"),
                InvestmentLedgerCommand.sell(decimal("1.00000000"), decimal("1.00000000"), decimal("0.60"), decimal("0.41"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroNegativeAndScaleOverflowInputs() {
        assertThatThrownBy(() -> calculator.calculate(InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("0.00000000"), decimal("1.00000000"), decimal("0.00"), decimal("0.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("1.000000001"), decimal("1.00000000"), decimal("0.00"), decimal("0.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("-1.00000000"), decimal("0.00"), decimal("0.00"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("1.00000000"), decimal("1.00000000"), decimal("0.001"), decimal("0.00"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesHighPrecisionQuantityAndLargeValuesWithoutFloatingPointConversion() {
        InvestmentCalculationResult result = calculator.calculate(
                InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("12345678901234567890.12345678"), decimal("2.00000000"), decimal("0.00"), decimal("0.00")));

        assertThat(result.newQuantity()).isEqualByComparingTo("12345678901234567890.12345678");
        assertThat(result.grossAmount()).isEqualByComparingTo("24691357802469135780.25");
    }

    @Test
    void rejectsPositionStatesWhoseQuantityAndCostDoNotMoveTogether() {
        assertThatThrownBy(() -> state("1.00000000", "0.00", "0.00"))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Open position must retain positive total cost");
        assertThatThrownBy(() -> state("0.00000000", "0.01", "0.00"))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Empty position cannot retain total cost");
    }

    @Test
    void rejectsInvalidPositionStateNullNegativeAndScaleOverflowValues() {
        assertThatThrownBy(() -> new InvestmentPositionState(null, decimal("0.00"), decimal("0.00")))
                .isInstanceOf(InvestmentLedgerValidationException.class);
        assertThatThrownBy(() -> state("-0.00000001", "0.00", "0.00"))
                .isInstanceOf(InvestmentLedgerValidationException.class);
        assertThatThrownBy(() -> state("1.000000001", "1.00", "0.00"))
                .isInstanceOf(InvestmentLedgerValidationException.class);
        assertThatThrownBy(() -> state("1.00000000", "1.001", "0.00"))
                .isInstanceOf(InvestmentLedgerValidationException.class);
        assertThatThrownBy(() -> new InvestmentPositionState(decimal("1.00000000"), decimal("1.00"), null))
                .isInstanceOf(InvestmentLedgerValidationException.class);
        assertThatThrownBy(() -> state("1.00000000", "-0.01", "0.00"))
                .isInstanceOf(InvestmentLedgerValidationException.class);
    }

    @Test
    void rejectsBuySellAndOpeningWhenMoneyRoundsToZero() {
        assertThatThrownBy(() -> calculator.calculate(InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("0.00000001"), decimal("0.00000001"), decimal("0.00"), decimal("0.00"))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Buy gross amount must be positive after CNY rounding");
        assertThatThrownBy(() -> calculator.calculate(InvestmentPositionState.empty(),
                InvestmentLedgerCommand.openingPosition(decimal("0.00000001"), decimal("0.00000001"))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Opening position total cost must be positive after CNY rounding");
        assertThatThrownBy(() -> calculator.calculate(state("1.00000000", "1.00", "0.00"),
                InvestmentLedgerCommand.sell(decimal("0.00000001"), decimal("0.00000001"), decimal("0.00"), decimal("0.00"))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Sell gross amount must be positive after CNY rounding");
    }

    @Test
    void rejectsPartialSellThatWouldLeavePositiveQuantityWithZeroCost() {
        assertThatThrownBy(() -> calculator.calculate(
                state("100000000.00000000", "0.01", "0.00"),
                InvestmentLedgerCommand.sell(decimal("99999999.00000000"), decimal("1.00000000"), decimal("0.00"), decimal("0.00"))))
                .isInstanceOf(InvestmentLedgerValidationException.class)
                .hasMessage("Partial sell cannot leave positive quantity with zero total cost");
    }

    @Test
    void acceptsTheLowestExpressibleCnyTradeAmount() {
        InvestmentCalculationResult result = calculator.calculate(InvestmentPositionState.empty(),
                InvestmentLedgerCommand.buy(decimal("0.01000000"), decimal("1.00000000"), decimal("0.00"), decimal("0.00")));

        assertThat(result.grossAmount()).isEqualByComparingTo("0.01");
        assertThat(result.newTotalCost()).isEqualByComparingTo("0.01");
    }

    private InvestmentPositionState state(String quantity, String totalCost, String cumulativeProfitLoss) {
        return new InvestmentPositionState(decimal(quantity), decimal(totalCost), decimal(cumulativeProfitLoss));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
