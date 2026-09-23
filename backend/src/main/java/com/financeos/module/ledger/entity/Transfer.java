package com.financeos.module.ledger.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("transfers")
public class Transfer {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("from_account_id")
    private Long fromAccountId;

    @TableField("to_account_id")
    private Long toAccountId;

    private BigDecimal amount;

    private String currency;

    private String description;

    @TableField("transacted_at")
    private LocalDateTime transactedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt = LocalDateTime.now();
}
