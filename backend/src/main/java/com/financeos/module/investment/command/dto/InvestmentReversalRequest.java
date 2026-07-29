package com.financeos.module.investment.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record InvestmentReversalRequest(String reason) {
}
