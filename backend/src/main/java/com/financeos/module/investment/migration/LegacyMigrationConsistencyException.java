package com.financeos.module.investment.migration;

public class LegacyMigrationConsistencyException extends RuntimeException {
    public LegacyMigrationConsistencyException(String message) {
        super(message);
    }
}
