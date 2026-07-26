package com.financeos.module.investment.migration;

import com.financeos.module.asset.entity.Asset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAssetMigrationCostResolverTest {

    private final LegacyAssetMigrationCostResolver resolver = new LegacyAssetMigrationCostResolver();

    @Test
    void preservesNonNullTotalCostAsAuthorityWhenCalculatorCanRebuildItExactly() {
        Asset asset = asset("3.00000000", "10.00000000", "30.01");

        LegacyOpeningCostResolution result = resolver.resolve(asset);

        assertThat(result.blockingErrors()).isEmpty();
        assertThat(result.totalCost()).isEqualByComparingTo("30.01");
        assertThat(result.totalCostSource()).isEqualTo("LEGACY_TOTAL_COST");
        assertThat(result.unitPrice()).isEqualByComparingTo("10.00333333");
        assertThat(result.expectedAvgCost()).isEqualByComparingTo("10.00333333");
    }

    @Test
    void derivesTotalCostOnlyWhenLegacyTotalCostIsNull() {
        Asset asset = asset("2.00000000", "10.12500000", null);

        LegacyOpeningCostResolution result = resolver.resolve(asset);

        assertThat(result.blockingErrors()).isEmpty();
        assertThat(result.totalCost()).isEqualByComparingTo("20.25");
        assertThat(result.totalCostSource()).isEqualTo("DERIVED_FROM_QUANTITY_AND_AVG_COST");
        assertThat(result.warnings()).contains("DERIVED_FROM_QUANTITY_AND_AVG_COST");
    }

    @Test
    void blocksCostThatCannotBeRebuiltExactlyByTheExistingCalculator() {
        Asset asset = asset("3000000.00000000", "0.00000033", "1.00");

        LegacyOpeningCostResolution result = resolver.resolve(asset);

        assertThat(result.blockingErrors()).contains("OPENING_COST_NOT_REPRESENTABLE");
    }

    private Asset asset(String quantity, String avgCost, String totalCost) {
        Asset asset = new Asset();
        asset.setQuantity(new BigDecimal(quantity));
        asset.setAvgCost(new BigDecimal(avgCost));
        asset.setTotalCost(totalCost == null ? null : new BigDecimal(totalCost));
        return asset;
    }
}
