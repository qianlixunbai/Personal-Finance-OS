package com.financeos.module.account.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountResponse(
        Long id,
        String name,
        String type,
        String currency,
        BigDecimal balance,
        String status,
        LocalDateTime createdAt
) {}
