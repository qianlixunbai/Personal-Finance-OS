package com.financeos.module.asset.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("assets")
public class Asset {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String name;

    private String symbol;

    private String type;

    private String market;

    private String currency;

    private BigDecimal quantity;

    @TableField("avg_cost")
    private BigDecimal avgCost;

    @TableField("current_price")
    private BigDecimal currentPrice;

    @TableField("market_value")
    private BigDecimal marketValue;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt = LocalDateTime.now();

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
