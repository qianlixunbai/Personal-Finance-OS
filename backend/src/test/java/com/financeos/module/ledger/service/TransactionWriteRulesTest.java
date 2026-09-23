package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The amount rule is now the single source of truth shared by manual transaction CRUD
 * and Transaction Import. These tests pin the contract so the two paths cannot drift
 * again, and assert that previously legal amounts stay legal.
 */
class TransactionWriteRulesTest {

    private final TransactionWriteRules rules = new TransactionWriteRules();

    @Test
    void acceptsScaleZeroOneAndTwoForPreviouslyLegalAmounts() {
        assertThatCode(() -> rules.validateAmount("INCOME", new BigDecimal("50"), "note")).doesNotThrowAnyException();
        assertThatCode(() -> rules.validateAmount("INCOME", new BigDecimal("50.0"), "note")).doesNotThrowAnyException();
        assertThatCode(() -> rules.validateAmount("INCOME", new BigDecimal("50.00"), "note")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAmountWithMoreThanTwoDecimals() {
        assertThatThrownBy(() -> rules.validateAmount("INCOME", new BigDecimal("1.005"), "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> rules.validateAmount("EXPENSE", new BigDecimal("0.001"), "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
    }

    @Test
    void rejectsAmountOutsideNumeric18Scale2Range() {
        assertThatThrownBy(() -> rules.validateAmount("INCOME", new BigDecimal("10000000000000000.00"), "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> rules.validateAmount("EXPENSE", new BigDecimal("-10000000000000000.00"), "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
    }

    @Test
    void acceptsTheExactNumeric18Scale2Boundary() {
        assertThatCode(() -> rules.validateAmount("INCOME", new BigDecimal("9999999999999999.99"), "note"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullAmountAndUnsupportedType() {
        assertThatThrownBy(() -> rules.validateAmount("INCOME", null, "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> rules.validateAmount("TRANSFER", new BigDecimal("10.00"), "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
    }

    @Test
    void requiresPositiveAmountForIncomeAndExpense() {
        assertThatThrownBy(() -> rules.validateAmount("INCOME", new BigDecimal("0.00"), "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> rules.validateAmount("EXPENSE", new BigDecimal("-1.00"), "note"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
    }

    @Test
    void adjustmentRequiresNonZeroAmountAndReason() {
        assertThatCode(() -> rules.validateAmount("ADJUSTMENT", new BigDecimal("-5.00"), "balance fix"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> rules.validateAmount("ADJUSTMENT", new BigDecimal("0.00"), "balance fix"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> rules.validateAmount("ADJUSTMENT", new BigDecimal("5.00"), "  "))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(400);
    }
}
