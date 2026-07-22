package com.financeos.module.investment.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class InvestmentLedgerCalculator {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal ZERO_QUANTITY = BigDecimal.ZERO.setScale(8);

    public InvestmentCalculationResult calculate(InvestmentPositionState current, InvestmentLedgerCommand command) {
        if (current == null) {
            throw new InvestmentLedgerValidationException("Current position is required");
        }
        if (command == null || command.transactionType() == null) {
            throw new InvestmentLedgerValidationException("Transaction type is required");
        }
        return switch (command.transactionType()) {
            case BUY -> calculateBuy(current, command);
            case SELL -> calculateSell(current, command);
            case DIVIDEND -> calculateDividend(current, command);
            case OPENING_POSITION -> calculateOpeningPosition(current, command);
        };
    }

    private InvestmentCalculationResult calculateBuy(InvestmentPositionState current, InvestmentLedgerCommand command) {
        validateQuantityAndUnitPrice(command);
        BigDecimal feeAmount = requireNonNegativeMoney(command.feeAmount(), "Fee amount");
        BigDecimal taxAmount = requireNonNegativeMoney(command.taxAmount(), "Tax amount");
        BigDecimal grossAmount = money(command.quantity().multiply(command.unitPrice()));
        BigDecimal acquiredCost = grossAmount.add(feeAmount).add(taxAmount);
        BigDecimal newQuantity = current.quantity().add(command.quantity());
        BigDecimal newTotalCost = current.totalCost().add(acquiredCost);
        BigDecimal newAverageCost = newTotalCost.divide(newQuantity, 8, RoundingMode.HALF_UP);

        return new InvestmentCalculationResult(
                grossAmount,
                acquiredCost,
                acquiredCost.negate(),
                newQuantity,
                newTotalCost,
                newAverageCost,
                ZERO_MONEY,
                ZERO_MONEY,
                current.cumulativeRealizedProfitLoss());
    }

    private InvestmentCalculationResult calculateSell(InvestmentPositionState current, InvestmentLedgerCommand command) {
        validateQuantityAndUnitPrice(command);
        if (current.quantity().signum() == 0 || command.quantity().compareTo(current.quantity()) > 0) {
            throw new InvestmentLedgerValidationException("Sell quantity exceeds current position");
        }
        BigDecimal feeAmount = requireNonNegativeMoney(command.feeAmount(), "Fee amount");
        BigDecimal taxAmount = requireNonNegativeMoney(command.taxAmount(), "Tax amount");
        BigDecimal grossAmount = money(command.quantity().multiply(command.unitPrice()));
        if (feeAmount.add(taxAmount).compareTo(grossAmount) > 0) {
            throw new InvestmentLedgerValidationException("Fee plus tax must not exceed sell gross amount");
        }
        BigDecimal netAmount = grossAmount.subtract(feeAmount).subtract(taxAmount);
        boolean fullSell = command.quantity().compareTo(current.quantity()) == 0;
        BigDecimal releasedCost = fullSell
                ? current.totalCost()
                : current.totalCost().multiply(command.quantity()).divide(current.quantity(), 2, RoundingMode.HALF_UP);
        BigDecimal newQuantity = fullSell ? ZERO_QUANTITY : current.quantity().subtract(command.quantity());
        BigDecimal newTotalCost = fullSell ? ZERO_MONEY : current.totalCost().subtract(releasedCost);
        BigDecimal realizedProfitLoss = netAmount.subtract(releasedCost);
        BigDecimal newCumulativeProfitLoss = current.cumulativeRealizedProfitLoss().add(realizedProfitLoss);
        BigDecimal newAverageCost = fullSell ? ZERO_QUANTITY : newTotalCost.divide(newQuantity, 8, RoundingMode.HALF_UP);

        return new InvestmentCalculationResult(
                grossAmount,
                netAmount,
                netAmount,
                newQuantity,
                newTotalCost,
                newAverageCost,
                releasedCost,
                realizedProfitLoss,
                newCumulativeProfitLoss);
    }

    private InvestmentCalculationResult calculateDividend(InvestmentPositionState current, InvestmentLedgerCommand command) {
        BigDecimal grossAmount = requirePositiveMoney(command.grossAmount(), "Dividend gross amount");
        BigDecimal feeAmount = requireNonNegativeMoney(command.feeAmount(), "Fee amount");
        BigDecimal taxAmount = requireNonNegativeMoney(command.taxAmount(), "Tax amount");
        if (feeAmount.add(taxAmount).compareTo(grossAmount) > 0) {
            throw new InvestmentLedgerValidationException("Fee plus tax must not exceed dividend gross amount");
        }
        BigDecimal netAmount = grossAmount.subtract(feeAmount).subtract(taxAmount);
        return new InvestmentCalculationResult(
                grossAmount,
                netAmount,
                netAmount,
                current.quantity(),
                current.totalCost(),
                averageCost(current),
                ZERO_MONEY,
                ZERO_MONEY,
                current.cumulativeRealizedProfitLoss());
    }

    private InvestmentCalculationResult calculateOpeningPosition(InvestmentPositionState current, InvestmentLedgerCommand command) {
        if (current.quantity().signum() != 0 || current.totalCost().signum() != 0) {
            throw new InvestmentLedgerValidationException("Opening position requires an empty current position");
        }
        validateQuantityAndUnitPrice(command);
        BigDecimal totalCost = money(command.quantity().multiply(command.unitPrice()));
        return new InvestmentCalculationResult(
                totalCost,
                ZERO_MONEY,
                ZERO_MONEY,
                command.quantity(),
                totalCost,
                totalCost.divide(command.quantity(), 8, RoundingMode.HALF_UP),
                ZERO_MONEY,
                ZERO_MONEY,
                current.cumulativeRealizedProfitLoss());
    }

    private void validateQuantityAndUnitPrice(InvestmentLedgerCommand command) {
        requirePositiveQuantity(command.quantity(), "Quantity");
        requirePositivePrice(command.unitPrice(), "Unit price");
    }

    private BigDecimal averageCost(InvestmentPositionState state) {
        return state.quantity().signum() == 0
                ? ZERO_QUANTITY
                : state.totalCost().divide(state.quantity(), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal requirePositiveQuantity(BigDecimal value, String name) {
        return requirePositive(value, 8, name).setScale(8);
    }

    private BigDecimal requirePositivePrice(BigDecimal value, String name) {
        return requirePositive(value, 8, name).setScale(8);
    }

    private BigDecimal requirePositiveMoney(BigDecimal value, String name) {
        return requirePositive(value, 2, name).setScale(2);
    }

    private BigDecimal requireNonNegativeMoney(BigDecimal value, String name) {
        BigDecimal checked = requireScale(value, 2, name);
        if (checked.signum() < 0) {
            throw new InvestmentLedgerValidationException(name + " must not be negative");
        }
        return checked.setScale(2);
    }

    private BigDecimal requirePositive(BigDecimal value, int scale, String name) {
        BigDecimal checked = requireScale(value, scale, name);
        if (checked.signum() <= 0) {
            throw new InvestmentLedgerValidationException(name + " must be positive");
        }
        return checked;
    }

    private BigDecimal requireScale(BigDecimal value, int scale, String name) {
        if (value == null) {
            throw new InvestmentLedgerValidationException(name + " is required");
        }
        if (value.scale() > scale) {
            throw new InvestmentLedgerValidationException(name + " scale must not exceed " + scale);
        }
        return value;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
