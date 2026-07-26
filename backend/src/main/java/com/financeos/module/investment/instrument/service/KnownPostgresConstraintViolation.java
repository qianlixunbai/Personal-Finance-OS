package com.financeos.module.investment.instrument.service;

final class KnownPostgresConstraintViolation {
    private KnownPostgresConstraintViolation() {
    }

    static boolean matches(Throwable throwable, String constraintName) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException exception
                    && "23505".equals(exception.getSQLState())
                    && exception.getMessage() != null
                    && exception.getMessage().contains("constraint \"" + constraintName + "\"")) {
                return true;
            }
        }
        return false;
    }
}
