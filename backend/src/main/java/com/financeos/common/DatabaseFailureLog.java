package com.financeos.common;

import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

/** Logs database failures without forwarding untrusted exception content to any appender. */
public final class DatabaseFailureLog {
    public enum Path {
        DATA_ACCESS, UNEXPECTED, BUSINESS, MIGRATION_CONSISTENCY, INVESTMENT_WRITE_CONSISTENCY,
        INVESTMENT_REVERSAL_CONSISTENCY, INVESTMENT_REPLACEMENT_CONSISTENCY, IMPORT_PREVIEW_CLEANUP
    }

    private enum Category {
        UNIQUE_CONFLICT, CHECK_CONSTRAINT, NUMERIC_OUT_OF_RANGE, LOCK_CONFLICT, DATABASE_ERROR
    }

    private DatabaseFailureLog() {
    }

    public static boolean hasDatabaseFailure(Throwable failure) {
        return category(failure) != null;
    }

    public static void warn(Logger logger, Path path, Throwable failure) {
        logger.warn("event=DB_FAILURE category={} path={} eventId={}",
                safeCategory(failure), path.name(), UUID.randomUUID().toString());
    }

    public static void error(Logger logger, Path path, Throwable failure) {
        logger.error("event=DB_FAILURE category={} path={} eventId={}",
                safeCategory(failure), path.name(), UUID.randomUUID().toString());
    }

    private static String safeCategory(Throwable failure) {
        Category category = category(failure);
        return (category == null ? Category.DATABASE_ERROR : category).name();
    }

    private static Category category(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) pending.add(failure);
        Category result = null;
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof DataAccessException || current instanceof PersistenceException
                    || current instanceof TransactionException) {
                result = Category.DATABASE_ERROR;
            }
            if (current instanceof SQLException sql) {
                Category sqlCategory = switch (sql.getSQLState() == null ? "" : sql.getSQLState()) {
                    case "23505" -> Category.UNIQUE_CONFLICT;
                    case "23514" -> Category.CHECK_CONSTRAINT;
                    case "22003" -> Category.NUMERIC_OUT_OF_RANGE;
                    case "55P03", "40P01" -> Category.LOCK_CONFLICT;
                    default -> Category.DATABASE_ERROR;
                };
                if (sqlCategory != Category.DATABASE_ERROR) return sqlCategory;
                result = sqlCategory;
                if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (current.getCause() != null) pending.add(current.getCause());
            Collections.addAll(pending, current.getSuppressed());
        }
        return result;
    }
}
