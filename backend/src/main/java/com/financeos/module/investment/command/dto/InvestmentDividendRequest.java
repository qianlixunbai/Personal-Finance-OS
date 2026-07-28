package com.financeos.module.investment.command.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record InvestmentDividendRequest(
        @NotBlank @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String grossAmount,
        @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String feeAmount,
        @JsonDeserialize(using = StrictDecimalStringDeserializer.class) String taxAmount,
        @Size(max = 100) String externalReference,
        @Size(max = 500) String note) {

    public InvestmentDividendRequest {
        feeAmount = feeAmount == null ? "0.00" : feeAmount;
        taxAmount = taxAmount == null ? "0.00" : taxAmount;
        externalReference = normalize(externalReference);
        note = normalize(note);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
