package com.financeos.module.investment.instrument.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financeos.module.investment.instrument.InstrumentStatus;
import com.financeos.module.investment.instrument.InvestmentAssetClass;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("investment_instruments")
public class InvestmentInstrument {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    private String symbol;
    private String name;
    private String market;

    @TableField("asset_class")
    private InvestmentAssetClass assetClass;

    @TableField("quote_currency")
    private String quoteCurrency;

    private InstrumentStatus status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
