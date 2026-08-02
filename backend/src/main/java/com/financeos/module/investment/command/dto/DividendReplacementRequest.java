package com.financeos.module.investment.command.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DividendReplacementRequest(
        @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String grossAmount,
        @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String feeAmount,
        @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String taxAmount,
        String externalReference, String note, String reason) implements InvestmentReplacementRequest { }
