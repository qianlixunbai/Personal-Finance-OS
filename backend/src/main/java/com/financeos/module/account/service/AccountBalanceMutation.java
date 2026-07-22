package com.financeos.module.account.service;

import java.math.BigDecimal;

public record AccountBalanceMutation(Long accountId, BigDecimal delta, boolean requireActive) {
}
