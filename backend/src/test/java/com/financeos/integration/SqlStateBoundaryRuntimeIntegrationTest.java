package com.financeos.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.common.ApiResponse;
import com.financeos.common.GlobalExceptionHandler;
import com.financeos.module.auth.util.JwtUtil;
import com.financeos.module.ledger.service.TransactionService;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Real socket HTTP regression for safe handling of database failures without a SQLState. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SqlStateBoundaryRuntimeIntegrationTest extends PostgresIntegrationTest {
    private static final String CANARY = "C4_SQLSTATE_BOUNDARY_SYNTHETIC_PRIVATE_CANARY";
    private static final long USER_ID = 1L;
    private static final long TRANSACTION_ID = 1L;

    @Autowired private TestRestTemplate http;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;
    @SpyBean private TransactionService transactions;
    @SpyBean private GlobalExceptionHandler handler;
    @LocalServerPort private int port;

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private SnapshotAppender appender;

    @BeforeEach
    void attachLogCapture() {
        jdbcTemplate.update("""
                INSERT INTO users(id, username, email, password_hash)
                VALUES (?, 'synthetic-boundary-user', 'c4-boundary@example.invalid', 'unused-test-hash')
                """, USER_ID);
        appender = new SnapshotAppender();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        root.detachAppender(appender);
        appender.stop();
    }

    private enum FailureShape { WITHOUT_SQL_CAUSE, WRAPPED_NULL_SQLSTATE }

    @ParameterizedTest
    @EnumSource(FailureShape.class)
    void nullSqlStateMustNotFallBackToSensitiveFrameworkLogging(FailureShape shape, CapturedOutput output) throws Exception {
        DataIntegrityViolationException failure;
        if (shape == FailureShape.WITHOUT_SQL_CAUSE) {
            failure = new DataIntegrityViolationException("synthetic SQL parameters=" + CANARY);
        } else {
            SQLException sql = new SQLException("synthetic failing row=" + CANARY + " amount=9876.54");
            assertThat(sql.getSQLState()).isNull();
            failure = new DataIntegrityViolationException("synthetic Spring SQL bindings=" + CANARY,
                    new PersistenceException("synthetic MyBatis parameters=" + CANARY, sql));
        }
        doThrow(failure).when(transactions).getById(USER_ID, TRANSACTION_ID);

        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        AtomicReference<ResponseEntity<?>> handlerResponse = new AtomicReference<>();
        doAnswer(invocation -> {
            try {
                ResponseEntity<?> result = (ResponseEntity<?>) invocation.callRealMethod();
                handlerResponse.set(result);
                return result;
            } catch (Throwable thrown) {
                handlerFailure.set(thrown);
                throw thrown;
            }
        }).when(handler).handleDataAccessException(same(failure));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtUtil.generateToken(USER_ID, "synthetic-boundary-user"));
        ResponseEntity<String> response = http.exchange("http://localhost:" + port + "/api/v1/transactions/" + TRANSACTION_ID,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // The real handler is invoked only by Spring on Tomcat's request thread, never called by this test.
        verify(transactions).getById(USER_ID, TRANSACTION_ID);
        verify(handler).handleDataAccessException(same(failure));
        verify(handler, never()).handleException(any());
        assertThat(handlerFailure.get()).as("real handler must complete without throwing").isNull();
        assertThat(handlerResponse.get()).isNotNull();
        assertThat(handlerResponse.get().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(handlerResponse.get().getBody()).isEqualTo(ApiResponse.error(503, "数据库服务暂时不可用"));
        assertThat(failure.getMessage()).contains(CANARY);
        if (shape == FailureShape.WRAPPED_NULL_SQLSTATE) {
            assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
            assertThat(failure.getMostSpecificCause().getMessage()).contains(CANARY, "9876.54");
        }

        List<ILoggingEvent> events = List.copyOf(appender.events);
        List<ILoggingEvent> application = events.stream()
                .filter(event -> event.getLoggerName().startsWith("com.financeos.")).toList();
        List<ILoggingEvent> framework = events.stream()
                .filter(event -> !event.getLoggerName().startsWith("com.financeos.")).toList();
        assertThat(framework.stream().filter(event -> event.getLoggerName()
                .contains("ExceptionHandlerExceptionResolver")).toList()).isEmpty();
        assertThat(framework.stream().filter(event -> event.getLoggerName().startsWith("org.apache.catalina.")
                && event.getLevel() == Level.ERROR).toList()).isEmpty();
        assertThat(application).hasSize(1);
        ILoggingEvent safeEvent = application.getFirst();
        assertThat(safeEvent.getLevel()).isEqualTo(Level.ERROR);
        assertThat(safeEvent.getMessage()).isEqualTo("event=DB_FAILURE category={} path={} eventId={}");
        assertThat(safeEvent.getFormattedMessage()).matches("event=DB_FAILURE category=DATABASE_ERROR"
                + " path=DATA_ACCESS eventId=[0-9a-f-]{36}");
        assertThat(safeEvent.getArgumentArray()).hasSize(3)
                .allSatisfy(argument -> assertThat(argument).isInstanceOf(String.class));
        assertThat(safeEvent.getArgumentArray()[0]).isEqualTo("DATABASE_ERROR");
        assertThat(safeEvent.getArgumentArray()[1]).isEqualTo("DATA_ACCESS");
        java.util.UUID.fromString((String) safeEvent.getArgumentArray()[2]);
        assertThat(safeEvent.getThrowableProxy()).isNull();

        PatternLayout layout = new PatternLayout();
        layout.setContext(root.getLoggerContext());
        layout.setPattern("%level %logger %msg%n%ex");
        layout.start();
        try {
            boolean applicationLeak = application.stream().anyMatch(event -> layout.doLayout(event).contains(CANARY));
            boolean formattedLeak = framework.stream().anyMatch(event -> event.getFormattedMessage().contains(CANARY));
            boolean argumentLeak = framework.stream().anyMatch(event -> Arrays.toString(event.getArgumentArray()).contains(CANARY));
            boolean throwableLeak = framework.stream().anyMatch(event -> event.getThrowableProxy() != null
                    && ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(CANARY));
            boolean renderedLeak = framework.stream().anyMatch(event -> layout.doLayout(event).contains(CANARY));
            boolean outputLeak = output.getAll().contains(CANARY);
            String handlerThrown = handlerFailure.get() == null ? "none" : handlerFailure.get().getClass().getName();
            StringBuilder evidence = new StringBuilder("shape=" + shape + "\nhttpStatus=" + response.getStatusCode().value()
                    + "\nhttpBody=" + response.getBody() + "\nhandlerThrown=" + handlerThrown
                    + "\napplicationEvents=" + application.size() + "\nframeworkEvents=" + framework.size()
                    + "\napplicationLeak=" + applicationLeak + "\nframeworkFormattedLeak=" + formattedLeak
                    + "\nframeworkArgumentLeak=" + argumentLeak + "\nframeworkThrowableLeak=" + throwableLeak
                    + "\nframeworkRenderedLeak=" + renderedLeak + "\ncapturedOutputLeak=" + outputLeak + "\n");
            for (ILoggingEvent event : events) {
                evidence.append("\nlogger=").append(event.getLoggerName()).append("\narguments=")
                        .append(Arrays.toString(event.getArgumentArray())).append("\n").append(layout.doLayout(event));
            }
            Files.writeString(Path.of("target", "c4-sqlstate-boundary-remediation-" + shape + ".txt"), evidence, StandardCharsets.UTF_8);
            System.out.println("C4_BOUNDARY_OBSERVATION shape=" + shape + " httpStatus=" + response.getStatusCode().value()
                    + " handlerThrown=" + handlerThrown
                    + " applicationLeak=" + applicationLeak + " frameworkFormattedLeak=" + formattedLeak
                    + " frameworkArgumentLeak=" + argumentLeak + " frameworkThrowableLeak=" + throwableLeak
                    + " frameworkRenderedLeak=" + renderedLeak + " capturedOutputLeak=" + outputLeak);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getContentType()).isNotNull();
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(objectMapper.readTree(response.getBody())).isEqualTo(objectMapper.readTree("""
                    {"code":503,"message":"数据库服务暂时不可用"}
                    """));
            assertThat(response.getBody()).doesNotContain(CANARY, "9876.54", "SQLException", "DataIntegrityViolationException");
            assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class)).isEqualTo("test");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isZero();
            for (ILoggingEvent event : events) {
                assertThat(event.getFormattedMessage()).doesNotContain(CANARY, "9876.54", "failing row");
                assertThat(Arrays.toString(event.getArgumentArray())).doesNotContain(CANARY, "9876.54", "failing row");
                if (event.getThrowableProxy() != null) {
                    assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                            .doesNotContain(CANARY, "9876.54", "NullPointerException");
                }
                assertThat(layout.doLayout(event)).doesNotContain(CANARY, "9876.54", "failing row");
            }
            assertThat(output.getAll()).doesNotContain("9876.54", "failing row", "NullPointerException", "Failure in @ExceptionHandler");
            assertSoftly(assertions -> {
                assertions.assertThat(applicationLeak).as("application logger final output leaks canary").isFalse();
                assertions.assertThat(formattedLeak).as("framework formatted message leaks canary").isFalse();
                assertions.assertThat(argumentLeak).as("framework logger arguments leak canary").isFalse();
                assertions.assertThat(throwableLeak).as("framework ThrowableProxy/cause leaks canary").isFalse();
                assertions.assertThat(renderedLeak).as("framework final rendered output leaks canary").isFalse();
                assertions.assertThat(outputLeak).as("actual stdout/stderr leaks canary").isFalse();
            });
        } finally {
            layout.stop();
        }
    }

    private static class SnapshotAppender extends AppenderBase<ILoggingEvent> {
        private final ConcurrentLinkedQueue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }
}
