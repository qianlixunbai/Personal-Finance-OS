package com.financeos.module.investment.migration;

import com.financeos.module.asset.entity.Asset;
import com.financeos.module.investment.ledger.InvestmentCalculationResult;
import com.financeos.module.investment.ledger.InvestmentLedgerCalculator;
import com.financeos.module.investment.ledger.InvestmentLedgerCommand;
import com.financeos.module.investment.ledger.InvestmentPositionState;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class LegacyAssetMigrationCostResolver {

    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    private final InvestmentLedgerCalculator calculator;

    public LegacyAssetMigrationCostResolver() {
        this(new InvestmentLedgerCalculator());
    }

    LegacyAssetMigrationCostResolver(InvestmentLedgerCalculator calculator) {
        this.calculator = calculator;
    }

    public LegacyOpeningCostResolution resolve(Asset asset) {
        List<String> blockingErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (asset == null || asset.getQuantity() == null || asset.getAvgCost() == null) {
            return unresolved(blockingErrors, warnings, "MISSING_POSITION_COST_DATA");
        }

        BigDecimal quantity = asset.getQuantity();
        BigDecimal avgCost = asset.getAvgCost();
        if (quantity.signum() <= 0 || quantity.scale() > 8) {
            return unresolved(blockingErrors, warnings, "INVALID_QUANTITY");
        }
        if (avgCost.signum() <= 0 || avgCost.scale() > 8) {
            return unresolved(blockingErrors, warnings, "INVALID_AVG_COST");
        }

        BigDecimal totalCost = asset.getTotalCost();
        String totalCostSource;
        BigDecimal legacyDerivedCost = quantity.multiply(avgCost).setScale(2, RoundingMode.HALF_UP);
        if (totalCost == null) {
            totalCost = legacyDerivedCost;
            totalCostSource = "DERIVED_FROM_QUANTITY_AND_AVG_COST";
            warnings.add(totalCostSource);
        } else {
            totalCostSource = "LEGACY_TOTAL_COST";
            if (totalCost.scale() > 2 || totalCost.signum() <= 0) {
                return unresolved(blockingErrors, warnings, "INVALID_TOTAL_COST");
            }
            totalCost = totalCost.setScale(2);
            BigDecimal difference = legacyDerivedCost.subtract(totalCost).abs();
            if (difference.compareTo(ONE_CENT) > 0) {
                return unresolved(blockingErrors, warnings, "LEGACY_COST_INCONSISTENT");
            }
            if (difference.signum() > 0) {
                warnings.add("LEGACY_COST_ROUNDING_DIFFERENCE");
            }
        }

        if (totalCost.signum() <= 0) {
            return unresolved(blockingErrors, warnings, "INVALID_TOTAL_COST");
        }
        BigDecimal unitPrice = totalCost.divide(quantity, 8, RoundingMode.HALF_UP);
        InvestmentCalculationResult result;
        try {
            result = calculator.calculate(InvestmentPositionState.empty(),
                    InvestmentLedgerCommand.openingPosition(quantity, unitPrice));
        } catch (IllegalArgumentException exception) {
            return unresolved(blockingErrors, warnings, "OPENING_COST_NOT_REPRESENTABLE");
        }
        if (result.grossAmount().compareTo(totalCost) != 0 || result.newTotalCost().compareTo(totalCost) != 0) {
            return unresolved(blockingErrors, warnings, "OPENING_COST_NOT_REPRESENTABLE");
        }
        if (result.newAvgCost().compareTo(avgCost) != 0) {
            warnings.add("DERIVED_AVG_COST_DIFFERS_FROM_LEGACY_AVG_COST");
        }
        return new LegacyOpeningCostResolution(totalCost, totalCostSource, unitPrice, result.newAvgCost(),
                List.copyOf(blockingErrors), List.copyOf(warnings));
    }

    private LegacyOpeningCostResolution unresolved(List<String> blockingErrors, List<String> warnings, String error) {
        blockingErrors.add(error);
        return new LegacyOpeningCostResolution(null, null, null, null,
                List.copyOf(blockingErrors), List.copyOf(warnings));
    }
}
