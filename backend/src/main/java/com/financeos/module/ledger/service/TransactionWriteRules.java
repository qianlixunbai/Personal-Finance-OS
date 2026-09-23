package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.category.entity.Category;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TransactionWriteRules {
    public static final String BASE_CURRENCY = "CNY";
    public static final int MONEY_SCALE = 2;
    public static final BigDecimal MAX_MONEY_AMOUNT = new BigDecimal("9999999999999999.99");

    /**
     * Single source of truth for ordinary-transaction amount rules.
     * Shared by manual CRUD and Transaction Import so the two paths cannot drift.
     * {@code transactions.amount} and {@code accounts.balance} are both NUMERIC(18,2);
     * without this guard PostgreSQL silently rounds the value, which would make the
     * stored amount and the API response disagree.
     */
    public void validateAmount(String type, BigDecimal amount, String description) {
        if (!"INCOME".equals(type) && !"EXPENSE".equals(type) && !"ADJUSTMENT".equals(type)) throw new BusinessException(400, "非法流水类型");
        if (amount == null || amount.scale() > MONEY_SCALE || amount.abs().compareTo(MAX_MONEY_AMOUNT) > 0) {
            throw new BusinessException(400, "流水金额不合法");
        }
        if (("INCOME".equals(type) || "EXPENSE".equals(type)) && amount.signum() <= 0) throw new BusinessException(400, "收入和支出金额必须大于 0");
        if ("ADJUSTMENT".equals(type) && (amount.signum() == 0 || description == null || description.isBlank())) throw new BusinessException(400, "余额调整金额或原因不合法");
    }

    public void validate(String type, BigDecimal amount, String currency, String description, Account account, Category category, Long userId) {
        validateAmount(type, amount, description);
        if (account == null || !userId.equals(account.getUserId())) throw new BusinessException(404, "账户不存在");
        if (!"ACTIVE".equals(account.getStatus()) || !BASE_CURRENCY.equals(account.getCurrency()) || !BASE_CURRENCY.equals(currency)) throw new BusinessException(400, "账户或币种不可用于新增流水");
        boolean visible = category != null && (userId.equals(category.getUserId()) || (category.getUserId() == null && Boolean.TRUE.equals(category.getIsSystem())));
        if (!visible) throw new BusinessException(404, "分类不存在");
        if (("INCOME".equals(type) || "EXPENSE".equals(type)) && !type.equals(category.getType())) throw new BusinessException(400, "分类与流水类型不兼容");
    }

    public BigDecimal balanceDelta(String type, BigDecimal amount) { return "EXPENSE".equals(type) ? amount.negate() : amount; }
}
