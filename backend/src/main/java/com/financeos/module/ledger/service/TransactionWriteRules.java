package com.financeos.module.ledger.service;

import com.financeos.common.BusinessException;
import com.financeos.module.account.entity.Account;
import com.financeos.module.category.entity.Category;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class TransactionWriteRules {
    public static final String BASE_CURRENCY = "CNY";

    public void validate(String type, BigDecimal amount, String currency, String description, Account account, Category category, Long userId) {
        if (!"INCOME".equals(type) && !"EXPENSE".equals(type) && !"ADJUSTMENT".equals(type)) throw new BusinessException(400, "非法流水类型");
        if (amount == null || amount.scale() != 2 || amount.abs().compareTo(new BigDecimal("9999999999999999.99")) > 0) throw new BusinessException(400, "流水金额不合法");
        if (("INCOME".equals(type) || "EXPENSE".equals(type)) && amount.signum() <= 0) throw new BusinessException(400, "收入和支出金额必须大于 0");
        if ("ADJUSTMENT".equals(type) && (amount.signum() == 0 || description == null || description.isBlank())) throw new BusinessException(400, "余额调整金额或原因不合法");
        if (account == null || !userId.equals(account.getUserId())) throw new BusinessException(404, "账户不存在");
        if (!"ACTIVE".equals(account.getStatus()) || !BASE_CURRENCY.equals(account.getCurrency()) || !BASE_CURRENCY.equals(currency)) throw new BusinessException(400, "账户或币种不可用于新增流水");
        boolean visible = category != null && (userId.equals(category.getUserId()) || (category.getUserId() == null && Boolean.TRUE.equals(category.getIsSystem())));
        if (!visible) throw new BusinessException(404, "分类不存在");
        if (("INCOME".equals(type) || "EXPENSE".equals(type)) && !type.equals(category.getType())) throw new BusinessException(400, "分类与流水类型不兼容");
    }

    public BigDecimal balanceDelta(String type, BigDecimal amount) { return "EXPENSE".equals(type) ? amount.negate() : amount; }
}
