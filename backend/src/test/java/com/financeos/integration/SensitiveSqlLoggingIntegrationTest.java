package com.financeos.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.auth.util.JwtUtil;
import com.financeos.module.ledger.dto.TransactionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SensitiveSqlLoggingIntegrationTest extends PostgresIntegrationTest {
    private static final String CANARY = "C4_SYNTHETIC_PRIVATE_FINANCE_CANARY";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;

    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> appender;
    private long userId;
    private long accountId;
    private long categoryId;

    @BeforeEach
    void captureAndSeed() {
        appender = new ListAppender<>();
        appender.setContext(rootLogger.getLoggerContext());
        appender.start();
        rootLogger.addAppender(appender);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users(username, email, password_hash)
                VALUES ('c4-test-user', 'c4-test@example.invalid', 'unused-test-hash') RETURNING id
                """, Long.class);
        accountId = jdbcTemplate.queryForObject("""
                INSERT INTO accounts(user_id, name, type, balance)
                VALUES (?, ?, 'CASH', 100.00) RETURNING id
                """, Long.class, userId, CANARY);
        categoryId = jdbcTemplate.queryForObject("""
                INSERT INTO categories(user_id, name, type) VALUES (?, 'test income', 'INCOME') RETURNING id
                """, Long.class, userId);
    }

    @AfterEach
    void detachCapture() {
        rootLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void uniqueConflictDoesNotLogSqlOrDuplicateValue(CapturedOutput output) throws Exception {
        // Test-only fixture; no production migration or business rule changes.
        jdbcTemplate.execute("CREATE UNIQUE INDEX test_c4_description_unique ON transactions(description)");
        try {
            performCreate("12.34").andExpect(status().isOk());
            MvcResult result = performCreate("12.34").andExpect(status().isConflict())
                    .andExpect(content().json("""
                            {"code":409,"message":"资源已存在或与现有数据冲突"}
                            """, true)).andReturn();
            assertSqlFailure(result, "23505", "Key (description)");
            assertDatabaseState(1, "112.34");
            assertSafeLogs(output, "UNIQUE_CONFLICT", "12.34");
        } finally {
            jdbcTemplate.execute("DROP INDEX test_c4_description_unique");
        }
    }

    @Test
    void failingRowCheckRollsBackInsertedFactAndBalanceWithoutLoggingValues(CapturedOutput output) throws Exception {
        jdbcTemplate.execute("ALTER TABLE accounts ADD CONSTRAINT test_c4_balance_check CHECK (balance <= 100.00)");
        try {
            MvcResult result = performCreate("9876.54").andExpect(status().isInternalServerError())
                    .andExpect(content().json("""
                            {"code":500,"message":"数据一致性校验失败"}
                            """, true)).andReturn();
            assertSqlFailure(result, "23514", "Failing row contains");
            assertDatabaseState(0, "100.00");
            assertSafeLogs(output, "CHECK_CONSTRAINT", "9876.54", "9976.54");
        } finally {
            jdbcTemplate.execute("ALTER TABLE accounts DROP CONSTRAINT test_c4_balance_check");
        }
    }

    @Test
    void triggerDetailRollsBackInsertedFactAndBalanceWithoutLoggingValues(CapturedOutput output) throws Exception {
        jdbcTemplate.execute("""
                CREATE FUNCTION test_c4_balance_fault() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'synthetic balance failure %', NEW.name
                        USING ERRCODE = '23514', DETAIL = NEW.name || ' amount=' || NEW.balance;
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_c4_balance_fault BEFORE UPDATE ON accounts
                FOR EACH ROW EXECUTE FUNCTION test_c4_balance_fault()
                """);
        try {
            MvcResult result = performCreate("9876.54").andExpect(status().isInternalServerError())
                    .andExpect(content().json("""
                            {"code":500,"message":"数据一致性校验失败"}
                            """, true)).andReturn();
            assertSqlFailure(result, "23514", "synthetic balance failure", "amount=9976.54");
            assertDatabaseState(0, "100.00");
            assertSafeLogs(output, "CHECK_CONSTRAINT", "9876.54", "9976.54");
        } finally {
            jdbcTemplate.execute("DROP TRIGGER test_c4_balance_fault ON accounts");
            jdbcTemplate.execute("DROP FUNCTION test_c4_balance_fault()");
        }
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(String amount) throws Exception {
        TransactionRequest request = new TransactionRequest(accountId, categoryId, "INCOME",
                new BigDecimal(amount), "CNY", CANARY, LocalDateTime.of(2026, 10, 11, 12, 0));
        return mockMvc.perform(post("/api/v1/transactions")
                .header("Authorization", "Bearer " + jwtUtil.generateToken(userId, "c4-test-user"))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)));
    }

    private void assertSqlFailure(MvcResult result, String sqlState, String... details) throws Exception {
        assertThat(result.getResolvedException()).isInstanceOf(DataIntegrityViolationException.class);
        Throwable cause = ((DataIntegrityViolationException) result.getResolvedException()).getMostSpecificCause();
        assertThat(cause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) cause).getSQLState()).isEqualTo(sqlState);
        // Confirm that the *real driver exception* still contains the canary; do not log it.
        assertThat(cause.getMessage()).contains(CANARY).contains(details);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(CANARY, "SQL", "Detail", "9876.54", "9976.54");
    }

    private void assertDatabaseState(int facts, String balance) {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(facts);
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?",
                BigDecimal.class, accountId)).isEqualByComparingTo(balance);
    }

    private void assertSafeLogs(CapturedOutput output, String category, String... amounts) {
        List<ILoggingEvent> events = appender.list.stream()
                .filter(event -> event.getLoggerName().startsWith("com.financeos.")).toList();
        assertThat(events).isNotEmpty();
        PatternLayout layout = new PatternLayout();
        layout.setContext(rootLogger.getLoggerContext());
        layout.setPattern("%level %logger %msg%n%ex");
        layout.start();
        try {
            for (ILoggingEvent event : events) {
                assertThat(event.getFormattedMessage()).doesNotContain(CANARY).doesNotContain(amounts);
                assertThat(Arrays.toString(event.getArgumentArray())).doesNotContain(CANARY).doesNotContain(amounts);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(layout.doLayout(event)).doesNotContain(CANARY, "INSERT INTO", "UPDATE accounts", "Failing row contains")
                        .doesNotContain(amounts);
            }
            assertThat(events).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("event=DB_FAILURE", "category=" + category, "path=DATA_ACCESS"));
            assertThat(output.getAll()).doesNotContain(CANARY, "Failing row contains").doesNotContain(amounts);
        } finally {
            layout.stop();
        }
    }
}
