package com.financeos.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.account.service.AccountQueryService;
import com.financeos.module.ai.dto.toolcalling.AiToolCall;
import com.financeos.module.ai.tool.FinanceToolDispatcher;
import com.financeos.module.ai.tool.FinancialOverviewTool;
import com.financeos.module.ai.tool.dto.FinancialOverviewFact;
import com.financeos.module.asset.service.AssetService;
import com.financeos.module.dashboard.dto.DashboardDto;
import com.financeos.module.dashboard.service.DashboardService;
import com.financeos.module.investment.command.InvestmentCommandService;
import com.financeos.module.investment.command.dto.FirstBuyRequest;
import com.financeos.module.investment.command.dto.InvestmentCommandResponse;
import com.financeos.module.investment.command.dto.InvestmentTradeRequest;
import com.financeos.module.ledger.dto.TransactionRequest;
import com.financeos.module.ledger.service.TransactionService;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(DashboardSnapshotConsistencyPostgresIntegrationTest.EvidenceConfiguration.class)
class DashboardSnapshotConsistencyPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final ThreadLocal<List<QueryEvidence>> EVIDENCE = new ThreadLocal<>();

    @Autowired private DashboardService dashboardService;
    @Autowired private InvestmentCommandService investmentCommandService;
    @Autowired private AssetService assetService;
    @Autowired private TransactionService transactionService;
    @Autowired private FinancialOverviewTool financialOverviewTool;
    @Autowired private FinanceToolDispatcher dispatcher;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private Clock clock;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private AccountQueryService accountQueryService;

    @Test
    void dashboardKeepsOneCommittedSnapshotWhileSameUsersBuyCommits() throws Exception {
        Fixture fixture = seed("snapshot-a");
        DashboardDto before = dashboardService.getDashboard(fixture.userId());
        assertDashboard(before, "1020.00", "40.00", fixture);

        ReadResult<DashboardDto> result = interleave(fixture,
                () -> dashboardService.getDashboard(fixture.userId()));
        DashboardDto after = dashboardService.getDashboard(fixture.userId());
        assertDashboard(after, "1037.00", "80.00", fixture);
        System.out.println("C2 dashboard before=" + before + ", concurrent=" + result.value() + ", after=" + after);
        assertThat(result.value()).as("one response must retain the pre-BUY snapshot").isEqualTo(before);
        assertRepeatableRead(result.queries());
        assertThat(AopUtils.isAopProxy(dashboardService)).isTrue();
    }

    @Test
    void httpDashboardKeepsSnapshotAndOtherUsersDataIsIsolated() throws Exception {
        Fixture first = seed("http-a");
        Fixture second = seed("http-b");
        DashboardDto before = dashboardService.getDashboard(first.userId());
        DashboardDto otherBefore = dashboardService.getDashboard(second.userId());
        ReadResult<DashboardDto> result = interleave(first, () -> {
            byte[] response = mockMvc.perform(get("/api/v1/dashboard").with(authentication(
                            new UsernamePasswordAuthenticationToken(first.userId(), null,
                                    List.of(new SimpleGrantedAuthority("ROLE_USER"))))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            return objectMapper.treeToValue(objectMapper.readTree(response).path("data"), DashboardDto.class);
        });
        // JsonNode may normalize numeric scale; compare the HTTP financial values numerically.
        assertThat(result.value()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(before);
        assertRepeatableRead(result.queries());
        assertThat(dashboardService.getDashboard(second.userId())).isEqualTo(otherBefore);
        assertDashboard(dashboardService.getDashboard(first.userId()), "1037.00", "80.00", first);
    }

    @Test
    void realFinancialOverviewToolAndDispatcherKeepTheDashboardSnapshot() throws Exception {
        Fixture fixture = seed("tool-a");
        FinancialOverviewFact before = financialOverviewTool.getFinancialOverview(fixture.userId());
        ReadResult<FinancialOverviewFact> result = interleave(fixture, () -> objectMapper.readValue(
                dispatcher.execute(fixture.userId(), new AiToolCall("overview", "getFinancialOverview", "{}")),
                FinancialOverviewFact.class));
        assertThat(result.value()).isEqualTo(before);
        assertRepeatableRead(result.queries());
        assertThat(financialOverviewTool.getFinancialOverview(fixture.userId()).accountingTotalAssets())
                .isEqualByComparingTo("1037.00");
    }

    @Test
    void outerReadCommittedCannotLowerSnapshotIsolationWhileBuyCommits() throws Exception {
        Fixture fixture = seed("outer-race");
        DashboardDto before = dashboardService.getDashboard(fixture.userId());
        AtomicInteger outerPid = new AtomicInteger();
        ReadResult<DashboardDto> result = interleave(fixture, () -> readCommittedTransaction().execute(status -> {
            Connection outer = DataSourceUtils.getConnection(dataSource);
            outerPid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            assertOuterTransaction(outer, outerPid.get());
            DashboardDto dashboard = dashboardService.getDashboard(fixture.userId());
            assertOuterTransaction(outer, outerPid.get());
            System.out.println("C2 outer-race restored outer PID=" + outerPid.get());
            return dashboard;
        }));
        assertThat(result.value()).isEqualTo(before);
        assertRepeatableRead(result.queries());
        assertThat(result.queries()).allSatisfy(query -> assertThat(query.pid()).isNotEqualTo(outerPid.get()));
        assertDashboard(dashboardService.getDashboard(fixture.userId()), "1037.00", "80.00", fixture);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentSnapshotHidesUncommittedWriteAndRestoresOuterCommitOrRollback(boolean rollback) {
        Fixture fixture = seed("outer-write");
        DashboardDto before = dashboardService.getDashboard(fixture.userId());
        readCommittedTransaction().executeWithoutResult(status -> {
            Connection outer = DataSourceUtils.getConnection(dataSource);
            int outerPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
            Long categoryId = jdbcTemplate.queryForObject("SELECT id FROM categories WHERE user_id = ? AND type = 'INCOME'",
                    Long.class, fixture.userId());
            transactionService.create(fixture.userId(), null, new TransactionRequest(fixture.accountId(), categoryId,
                    "INCOME", new BigDecimal("7.00"), "CNY", "outer-uncommitted",
                    LocalDateTime.now(clock).withDayOfMonth(1).minusHours(1)));
            assertOuterTransaction(outer, outerPid);
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class,
                    fixture.accountId())).isEqualByComparingTo("987.00");

            List<QueryEvidence> queries = new ArrayList<>();
            EVIDENCE.set(queries);
            try {
                assertThat(dashboardService.getDashboard(fixture.userId())).isEqualTo(before);
            } finally {
                EVIDENCE.remove();
            }
            assertRepeatableRead(queries);
            assertThat(queries).allSatisfy(query -> assertThat(query.pid()).isNotEqualTo(outerPid));
            assertOuterTransaction(outer, outerPid);
            assertThat(status.isRollbackOnly()).as("Dashboard must not mark the outer transaction rollback-only").isFalse();
            assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class,
                    fixture.accountId())).isEqualByComparingTo("987.00");
            System.out.println("C2 outer-write rollback=" + rollback + ", outer PID=" + outerPid + ", inner=" + queries);
            if (rollback) status.setRollbackOnly();
        });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class,
                fixture.accountId())).isEqualByComparingTo(rollback ? "980.00" : "987.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class,
                fixture.userId())).isEqualTo(rollback ? 2 : 3);
        if (rollback) {
            assertThat(dashboardService.getDashboard(fixture.userId())).isEqualTo(before);
        } else {
            assertThat(dashboardService.getDashboard(fixture.userId()).totalAssets()).isEqualByComparingTo("1027.00");
        }
    }

    @Test
    void emptyDashboardKeepsZeroValuesSixMonthsAndReadOnlyTransaction() {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES ('empty', 'empty@example.com', 'hash') RETURNING id",
                Long.class);
        List<QueryEvidence> queries = new ArrayList<>();
        EVIDENCE.set(queries);
        DashboardDto dashboard;
        try {
            dashboard = dashboardService.getDashboard(userId);
        } finally {
            EVIDENCE.remove();
        }
        assertThat(dashboard.totalAssets()).isEqualByComparingTo("0");
        assertThat(dashboard.netWorth()).isEqualByComparingTo("0");
        assertThat(dashboard.monthIncome()).isEqualByComparingTo("0");
        assertThat(dashboard.monthExpense()).isEqualByComparingTo("0");
        assertThat(dashboard.monthNet()).isEqualByComparingTo("0");
        assertThat(dashboard.assetAllocation()).isEmpty();
        assertThat(dashboard.recentTransactions()).isEmpty();
        assertThat(dashboard.monthlyCashFlowTrend()).hasSize(6).allSatisfy(month -> {
            assertThat(month.income()).isEqualByComparingTo("0");
            assertThat(month.expense()).isEqualByComparingTo("0");
            assertThat(month.net()).isEqualByComparingTo("0");
        });
        assertRepeatableRead(queries, 6);
    }

    private TransactionTemplate readCommittedTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private void assertOuterTransaction(Connection expectedConnection, int expectedPid) {
        assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(expectedConnection);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(expectedPid);
        assertThat(jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        assertThat(jdbcTemplate.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("off");
    }

    private <T> ReadResult<T> interleave(Fixture fixture, Callable<T> read) throws Exception {
        CountDownLatch balanceRead = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object balance = invocation.callRealMethod();
            if (EVIDENCE.get() != null) {
                assertThat(balance).isEqualTo(new BigDecimal("980.00"));
                balanceRead.countDown();
                if (!resume.await(15, TimeUnit.SECONDS)) {
                    throw new AssertionError("Dashboard continuation timed out");
                }
            }
            return balance;
        }).when(accountQueryService).sumBalanceByUser(fixture.userId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ReadResult<T>> reader = null;
        Future<InvestmentCommandResponse> writer = null;
        try {
            reader = executor.submit(() -> {
                List<QueryEvidence> queries = new ArrayList<>();
                EVIDENCE.set(queries);
                try {
                    return new ReadResult<>(read.call(), List.copyOf(queries));
                } finally {
                    EVIDENCE.remove();
                }
            });
            assertThat(balanceRead.await(15, TimeUnit.SECONDS)).as("real balance query completed").isTrue();
            writer = executor.submit(() -> investmentCommandService.buy(fixture.userId(), fixture.assetId(),
                    "concurrent-buy", new InvestmentTradeRequest("2", "10", "3", "0")));
            // The non-transactional facade returns only after the real transactional service has committed.
            InvestmentCommandResponse receipt = writer.get(10, TimeUnit.SECONDS);
            assertThat(receipt.transactionType()).isEqualTo("BUY");
            assertThat(receipt.idempotentReplay()).isFalse();
            assertThat(receipt.balanceAfter()).isEqualTo("957.00");
            assertThat(receipt.cashDelta()).isEqualTo("-23.00");
            assertThat(receipt.finalPosition().quantity()).isEqualTo("4.00000000");
            assertThat(receipt.finalPosition().projectionVersion()).isEqualTo(2);
            assertThat(reader.isDone()).as("BUY commits while Dashboard remains paused").isFalse();
            assertCommittedBuy(fixture, receipt);
            resume.countDown();
            ReadResult<T> result = reader.get(15, TimeUnit.SECONDS);
            System.out.println("C2 JDBC/PG evidence=" + result.queries());
            // Recheck the affected balance, projection and facts after the read.
            assertCommittedBuy(fixture, receipt);
            return result;
        } finally {
            resume.countDown();
            if (reader != null) reader.cancel(true);
            if (writer != null) writer.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("workers released").isTrue();
        }
    }

    private void assertCommittedBuy(Fixture fixture, InvestmentCommandResponse receipt) {
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ? AND user_id = ?",
                BigDecimal.class, fixture.accountId(), fixture.userId())).isEqualByComparingTo("957.00");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT quantity, total_cost, projection_version, last_transaction_id, current_price
                FROM assets WHERE id = ? AND user_id = ?
                """, fixture.assetId(), fixture.userId()))
                .containsEntry("quantity", new BigDecimal("4.00000000"))
                .containsEntry("total_cost", new BigDecimal("43.00"))
                .containsEntry("projection_version", 2)
                .containsEntry("last_transaction_id", receipt.transactionId())
                .containsEntry("current_price", new BigDecimal("20.0000"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_transactions WHERE user_id = ?",
                Integer.class, fixture.userId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT net_amount FROM investment_transactions WHERE id = ? AND user_id = ? AND transaction_type = 'BUY' AND status = 'POSTED'",
                BigDecimal.class, receipt.transactionId(), fixture.userId())).isEqualByComparingTo("23.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?",
                Integer.class, fixture.userId())).isEqualTo(2);
    }

    private void assertRepeatableRead(List<QueryEvidence> queries) {
        assertRepeatableRead(queries, 8);
    }

    private void assertRepeatableRead(List<QueryEvidence> queries, int expectedQueries) {
        assertThat(queries).hasSize(expectedQueries);
        assertThat(queries.stream().map(QueryEvidence::connection).distinct().count()).isEqualTo(1);
        assertThat(queries.stream().map(QueryEvidence::pid).distinct().count()).isEqualTo(1);
        assertThat(queries.stream().map(QueryEvidence::snapshot).distinct().count()).isEqualTo(1);
        assertThat(queries).allSatisfy(query -> {
            assertThat(query.springTransaction()).isTrue();
            assertThat(query.springReadOnly()).isTrue();
            assertThat(query.autoCommit()).isFalse();
            assertThat(query.jdbcIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(query.pgIsolation()).isEqualTo("repeatable read");
            assertThat(query.pgReadOnly()).isEqualTo("on");
            assertThat(query.sql()).startsWithIgnoringCase("SELECT").doesNotContainIgnoringCase("FOR UPDATE");
        });
    }

    private void assertDashboard(DashboardDto dashboard, String total, String assetValue, Fixture fixture) {
        assertThat(dashboard.totalAssets()).isEqualByComparingTo(total);
        assertThat(dashboard.netWorth()).isEqualByComparingTo(total);
        assertThat(dashboard.monthIncome()).isEqualByComparingTo("1100.00");
        assertThat(dashboard.monthExpense()).isEqualByComparingTo("100.00");
        assertThat(dashboard.monthNet()).isEqualByComparingTo("1000.00");
        assertThat(dashboard.assetAllocation()).containsExactly(new DashboardDto.AssetAllocation(
                fixture.marker(), new BigDecimal(assetValue).setScale(12), new BigDecimal("100.0000")));
        assertThat(dashboard.monthlyCashFlowTrend()).hasSize(6);
        assertThat(dashboard.monthlyCashFlowTrend().getLast().net()).isEqualByComparingTo("1000.00");
        assertThat(dashboard.recentTransactions()).hasSize(2).allSatisfy(transaction -> {
            assertThat(transaction.account()).isEqualTo(fixture.marker());
            assertThat(transaction.category()).startsWith(fixture.marker());
        });
    }

    private Fixture seed(String marker) {
        Long userId = jdbcTemplate.queryForObject("INSERT INTO users (username, email, password_hash) VALUES (?, ?, 'hash') RETURNING id",
                Long.class, marker, marker + "@example.com");
        Long accountId = jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, type, currency, balance) VALUES (?, ?, 'BROKERAGE', 'CNY', 0) RETURNING id",
                Long.class, userId, marker);
        Long instrumentId = jdbcTemplate.queryForObject("""
                INSERT INTO investment_instruments (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, ?, ?, 'FUND', 'FUND', 'CNY', 'ACTIVE') RETURNING id
                """, Long.class, userId, marker.toUpperCase(), marker);
        LocalDateTime time = LocalDateTime.now(clock).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        for (String type : List.of("INCOME", "EXPENSE")) {
            Long categoryId = jdbcTemplate.queryForObject("INSERT INTO categories (user_id, name, type, is_system) VALUES (?, ?, ?, false) RETURNING id",
                    Long.class, userId, marker + type, type);
            transactionService.create(userId, null, new TransactionRequest(accountId, categoryId, type,
                    new BigDecimal(type.equals("INCOME") ? "1100.00" : "100.00"), "CNY", marker,
                    type.equals("INCOME") ? time : time.plusMinutes(1)));
        }
        InvestmentCommandResponse firstBuy = investmentCommandService.firstBuy(userId, "fixture-buy",
                new FirstBuyRequest(accountId, instrumentId, "2", "10", "0", "0"));
        assetService.updatePrice(userId, firstBuy.assetId(), new BigDecimal("20.00"));
        return new Fixture(userId, accountId, firstBuy.assetId(), marker);
    }

    private record Fixture(Long userId, Long accountId, Long assetId, String marker) {}
    private record ReadResult<T>(T value, List<QueryEvidence> queries) {}
    private record QueryEvidence(String sql, Connection connection, int pid, String snapshot,
                                 boolean springTransaction, boolean springReadOnly, boolean autoCommit,
                                 int jdbcIsolation, String pgIsolation, String pgReadOnly) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class EvidenceConfiguration {
        @Bean
        @Primary
        Clock snapshotTestClock() {
            return Clock.fixed(Instant.parse("2026-10-11T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        }

        @Bean
        Interceptor dashboardQueryEvidenceInterceptor() {
            return new QueryEvidenceInterceptor();
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    static class QueryEvidenceInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            List<QueryEvidence> queries = EVIDENCE.get();
            if (queries != null) {
                Connection connection = (Connection) invocation.getArgs()[0];
                String sql = ((StatementHandler) invocation.getTarget()).getBoundSql().getSql().strip();
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("""
                        SELECT pg_backend_pid(), txid_current_snapshot()::text,
                               current_setting('transaction_isolation'), current_setting('transaction_read_only')
                        """)) {
                    assertThat(result.next()).isTrue();
                    queries.add(new QueryEvidence(sql, connection, result.getInt(1), result.getString(2),
                            TransactionSynchronizationManager.isActualTransactionActive(),
                            TransactionSynchronizationManager.isCurrentTransactionReadOnly(), connection.getAutoCommit(),
                            connection.getTransactionIsolation(), result.getString(3), result.getString(4)));
                }
            }
            return invocation.proceed();
        }
    }
}
