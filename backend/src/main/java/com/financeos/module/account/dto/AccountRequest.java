package com.financeos.module.account.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record AccountRequest(
        @NotBlank String name,
        @NotBlank String type,
        String currency
) {}
