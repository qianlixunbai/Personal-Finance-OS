package com.financeos.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.service.TransactionImportPreviewCleanupService;
import com.financeos.module.importing.storage.TemporaryImportFileStorage;
import com.financeos.module.investment.command.InvestmentReplacementConsistencyException;
import com.financeos.module.investment.command.InvestmentReversalConsistencyException;
import com.financeos.module.investment.command.InvestmentWriteConsistencyException;
import com.financeos.module.investment.migration.LegacyMigrationConsistencyException;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SensitiveSqlLoggingTest {
    private static final String CANARY = "C4_SYNTHETIC_SQL_AND_PARAMETER_CANARY";
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachCapture() {
        appender = new ListAppender<>();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachCapture() {
        root.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @CsvSource({
            "23505,409,UNIQUE_CONFLICT,资源已存在或与现有数据冲突",
            "23514,500,CHECK_CONSTRAINT,数据一致性校验失败",
            "22003,400,NUMERIC_OUT_OF_RANGE,数值超出允许范围",
            "55P03,409,LOCK_CONFLICT,并发操作冲突，请重试",
            "40P01,409,LOCK_CONFLICT,并发操作冲突，请重试",
            "08006,503,DATABASE_ERROR,数据库服务暂时不可用"
    })
    void dataAccessLoggingKeepsOriginalHttpContract(String state, int status, String category,
                                                  String message, CapturedOutput output) {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataAccessException(databaseFailure(state));
        assertResponse(response, status, message);
        assertSafeEvent("DATA_ACCESS", category, output);
    }

    private enum Wrapper { DIRECT_SQL, NESTED, SPRING, MYBATIS, TRANSACTION, SUPPRESSED, SQL_NEXT, CYCLE, UNKNOWN_STATE, NULL_STATE }

    @ParameterizedTest
    @EnumSource(Wrapper.class)
    void genericHandlerDetectsDatabaseFailuresThroughoutExceptionGraph(Wrapper wrapper, CapturedOutput output) {
        Exception failure = switch (wrapper) {
            case DIRECT_SQL -> new SQLException(CANARY, "23514");
            case NESTED -> new IllegalStateException(CANARY, new RuntimeException(CANARY, databaseFailure("23514")));
            case SPRING -> new DataIntegrityViolationException(CANARY);
            case MYBATIS -> new PersistenceException(CANARY);
            case TRANSACTION -> new TransactionSystemException(CANARY);
            case SUPPRESSED -> {
                var exception = new IllegalStateException(CANARY);
                exception.addSuppressed(databaseFailure("23514"));
                yield exception;
            }
            case SQL_NEXT -> {
                var sql = new SQLException(CANARY);
                sql.setNextException(new SQLException(CANARY, "23514"));
                yield new IllegalStateException(CANARY, sql);
            }
            case CYCLE -> {
                var first = new IllegalStateException(CANARY);
                var second = new IllegalStateException(CANARY, first);
                first.initCause(second);
                second.addSuppressed(databaseFailure("23514"));
                yield first;
            }
            case UNKNOWN_STATE -> new SQLException(CANARY, CANARY + "\nforged-log-line");
            case NULL_STATE -> new SQLException(CANARY);
        };
        assertResponse(handler.handleException(failure), 500, "服务器内部错误");
        String category = switch (wrapper) {
            case SPRING, MYBATIS, TRANSACTION, UNKNOWN_STATE, NULL_STATE -> "DATABASE_ERROR";
            default -> "CHECK_CONSTRAINT";
        };
        assertSafeEvent("UNEXPECTED", category, output);
    }

    private enum ConsistencyPath { MIGRATION, WRITE, REVERSAL, REPLACEMENT, BUSINESS }

    @ParameterizedTest
    @EnumSource(ConsistencyPath.class)
    void specializedWrappersDoNotForwardTheirSqlCauses(ConsistencyPath path, CapturedOutput output) {
        ResponseEntity<ApiResponse<Void>> response;
        String message;
        String logPath;
        switch (path) {
            case MIGRATION -> {
                var failure = new LegacyMigrationConsistencyException(CANARY);
                failure.initCause(databaseFailure("23514"));
                response = handler.handleMigrationConsistencyException(failure);
                message = "Migration consistency check failed";
                logPath = "MIGRATION_CONSISTENCY";
            }
            case WRITE -> {
                var failure = new InvestmentWriteConsistencyException(CANARY);
                failure.initCause(databaseFailure("23514"));
                response = handler.handleInvestmentWriteConsistencyException(failure);
                message = "Investment write consistency check failed";
                logPath = "INVESTMENT_WRITE_CONSISTENCY";
            }
            case REVERSAL -> {
                response = handler.handleInvestmentReversalConsistencyException(
                        new InvestmentReversalConsistencyException(CANARY, databaseFailure("23514")));
                message = "Investment reversal consistency check failed";
                logPath = "INVESTMENT_REVERSAL_CONSISTENCY";
            }
            case REPLACEMENT -> {
                response = handler.handleInvestmentReplacementConsistencyException(
                        new InvestmentReplacementConsistencyException(CANARY, databaseFailure("23514")));
                message = "Investment replacement consistency check failed";
                logPath = "INVESTMENT_REPLACEMENT_CONSISTENCY";
            }
            default -> {
                var failure = new BusinessException(409, "fixed business conflict");
                failure.initCause(databaseFailure("23514"));
                response = handler.handleBusinessException(failure);
                message = "fixed business conflict";
                logPath = "BUSINESS";
            }
        }
        assertResponse(response, path == ConsistencyPath.BUSINESS ? 409 : 500, message);
        assertSafeEvent(logPath, "CHECK_CONSTRAINT", output);
    }

    @Test
    void expiredImportCleanupDoesNotLogMapperFailure(CapturedOutput output) {
        var mapper = mock(TransactionImportSessionMapper.class);
        var storage = mock(TemporaryImportFileStorage.class);
        var planStorage = mock(TemporaryImportFileStorage.class);
        var clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);
        var session = new TransactionImportSession();
        session.setId(UUID.randomUUID());
        session.setUserId(1L);
        session.setTemporaryStorageReference("synthetic-test-file");
        when(mapper.findExpiredForCleanup(any())).thenReturn(List.of(session));
        var lifecycle = mock(com.financeos.module.importing.service.TransactionImportFileLifecycleService.class);
        when(lifecycle.reconcileTerminal(anyLong(), any(), any())).thenThrow(databaseFailure("23514"));
        new TransactionImportPreviewCleanupService(mapper, storage, planStorage, clock, lifecycle).cleanupExpired();
        verify(storage, org.mockito.Mockito.never()).delete(any());
        verify(lifecycle).reconcileTerminal(session.getUserId(), session.getId(), clock.instant());
        assertSafeEvent("IMPORT_PREVIEW_CLEANUP", "CHECK_CONSTRAINT", output);
    }

    @Test
    void ordinaryNonDatabaseErrorRetainsDiagnosticsAndResponse(CapturedOutput output) {
        assertResponse(handler.handleException(new IllegalStateException("ordinary non-database failure")),
                500, "服务器内部错误");
        ILoggingEvent event = applicationEvents().getFirst();
        assertThat(event.getFormattedMessage()).isEqualTo("Unexpected error");
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(output.getAll()).contains("ordinary non-database failure");
    }

    @Test
    void ordinaryImportDomainErrorRetainsContract(CapturedOutput output) {
        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(409, "IMPORT_LOCK_CONFLICT"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(ApiResponse.domainError(409, "IMPORT_LOCK_CONFLICT", true));
        assertThat(output.getAll()).contains("Business exception: IMPORT_LOCK_CONFLICT");
    }

    @Test
    void failuresReceiveDistinctServerGeneratedEventIds(CapturedOutput output) {
        handler.handleException(new SQLException(CANARY));
        handler.handleException(new SQLException(CANARY));
        List<ILoggingEvent> events = applicationEvents();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getArgumentArray()[2]).isNotEqualTo(events.get(1).getArgumentArray()[2]);
        assertThat(output.getAll()).doesNotContain(CANARY);
    }

    private DataIntegrityViolationException databaseFailure(String state) {
        return new DataIntegrityViolationException("SQL INSERT INTO private VALUES ('" + CANARY + "')",
                new SQLException("Failing row contains " + CANARY + " amount=12345.67", state));
    }

    private void assertResponse(ResponseEntity<ApiResponse<Void>> response, int status, String message) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(ApiResponse.error(status, message));
    }

    private List<ILoggingEvent> applicationEvents() {
        return appender.list.stream().filter(event -> event.getLoggerName().startsWith("com.financeos.")).toList();
    }

    private void assertSafeEvent(String path, String category, CapturedOutput output) {
        List<ILoggingEvent> events = applicationEvents();
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.getFirst();
        assertThat(event.getFormattedMessage()).matches("event=DB_FAILURE category=" + category
                + " path=" + path + " eventId=[0-9a-f-]{36}");
        assertThat(event.getMessage()).isEqualTo("event=DB_FAILURE category={} path={} eventId={}");
        assertThat(event.getArgumentArray()).hasSize(3).allSatisfy(argument -> assertThat(argument).isInstanceOf(String.class));
        assertThat(event.getArgumentArray()[0]).isEqualTo(category);
        assertThat(event.getArgumentArray()[1]).isEqualTo(path);
        UUID.fromString((String) event.getArgumentArray()[2]);
        assertThat(event.getThrowableProxy()).isNull();
        PatternLayout layout = new PatternLayout();
        layout.setContext(root.getLoggerContext());
        layout.setPattern("%msg%n%ex");
        layout.start();
        try {
            assertThat(layout.doLayout(event)).doesNotContain(CANARY, "12345.67", "INSERT INTO", "Failing row contains");
            assertThat(output.getAll()).doesNotContain(CANARY, "12345.67", "INSERT INTO", "Failing row contains");
        } finally {
            layout.stop();
        }
    }
}
