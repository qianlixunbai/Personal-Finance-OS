package com.financeos.module.asset.marketdata.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

@Data
@TableName("market_quotes")
public class MarketQuote {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String market = "US";
    private String symbol;
    private String currency;
    private BigDecimal price;

    @TableField("quote_time")
    private Instant quoteTime;

    @TableField("fetched_at")
    private Instant fetchedAt;

    private String provider = "TWELVE_DATA";

    @TableField("created_at")
    private Instant createdAt = Instant.now();

    @TableField("updated_at")
    private Instant updatedAt = Instant.now();

    public void setSymbol(String symbol) {
        this.symbol = normalizeSymbol(symbol);
    }

    public void prepareForPersistence() {
        setSymbol(symbol);
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("Market quote price must be positive");
        }
        if (quoteTime == null) {
            throw new IllegalArgumentException("Market quote time is required");
        }
        if (fetchedAt == null) {
            fetchedAt = Instant.now();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    private String normalizeSymbol(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Market quote symbol is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
