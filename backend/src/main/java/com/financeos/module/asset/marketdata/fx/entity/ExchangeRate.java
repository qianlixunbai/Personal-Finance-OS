package com.financeos.module.asset.marketdata.fx.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

@Data
@TableName("exchange_rates")
public class ExchangeRate {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String baseCurrency;
    private String quoteCurrency;
    private BigDecimal rate;

    @TableField("rate_time")
    private Instant rateTime;

    @TableField("fetched_at")
    private Instant fetchedAt;

    private String provider;

    @TableField("created_at")
    private Instant createdAt = Instant.now();

    @TableField("updated_at")
    private Instant updatedAt = Instant.now();

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = normalizeCurrency(baseCurrency, "Base currency");
    }

    public void setQuoteCurrency(String quoteCurrency) {
        this.quoteCurrency = normalizeCurrency(quoteCurrency, "Quote currency");
    }

    public void prepareForPersistence() {
        setBaseCurrency(baseCurrency);
        setQuoteCurrency(quoteCurrency);
        if (baseCurrency.equals(quoteCurrency)) {
            throw new IllegalArgumentException("Base currency and quote currency must differ");
        }
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Exchange rate must be positive");
        }
        if (rateTime == null) {
            throw new IllegalArgumentException("Exchange rate time is required");
        }
        if (provider == null || provider.trim().isEmpty()) {
            throw new IllegalArgumentException("Exchange rate provider is required");
        }
        provider = provider.trim();
        if (fetchedAt == null) {
            fetchedAt = Instant.now();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    private String normalizeCurrency(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(fieldName + " must be a three-letter code");
        }
        return normalized;
    }
}
