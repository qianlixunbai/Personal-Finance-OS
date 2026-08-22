package com.financeos.module.importing.dto;

public record TransactionImportConfirmResponse(TransactionImportReceipt receipt, boolean idempotentReplay) { }
