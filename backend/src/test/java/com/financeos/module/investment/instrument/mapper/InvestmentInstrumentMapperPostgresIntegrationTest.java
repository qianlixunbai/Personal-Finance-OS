package com.financeos.module.investment.instrument.mapper;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.investment.instrument.InstrumentStatus;
import com.financeos.module.investment.instrument.InvestmentAssetClass;
import com.financeos.module.investment.instrument.entity.InvestmentInstrument;
import com.financeos.module.investment.instrument.service.CreateInvestmentInstrumentCommand;
import com.financeos.module.investment.instrument.service.InvestmentInstrumentCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentInstrumentMapperPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private InvestmentInstrumentMapper instrumentMapper;

    @Autowired
    private InvestmentInstrumentCommandService commandService;

    @Test
    void persistsAndQueriesInstrumentsOnlyWithinTheirUserBoundary() {
        Long firstUser = insertUser("instrument-first");
        Long secondUser = insertUser("instrument-second");
        InvestmentInstrument first = instrument(firstUser, "AAPL", "US", InvestmentAssetClass.STOCK);
        InvestmentInstrument second = instrument(secondUser, "AAPL", "US", InvestmentAssetClass.STOCK);
        instrumentMapper.insert(first);
        instrumentMapper.insert(second);

        assertThat(instrumentMapper.findByUserIdAndId(firstUser, first.getId()).getId()).isEqualTo(first.getId());
        assertThat(instrumentMapper.findByUserIdAndId(secondUser, first.getId())).isNull();
        assertThat(instrumentMapper.findByUserIdAndMarketAndSymbol(secondUser, "US", "AAPL").getId())
                .isEqualTo(second.getId());
    }

    @Test
    void enforcesInstrumentCanonicalAndUniqueConstraintsInPostgres() {
        Long user = insertUser("instrument-constraints");
        instrumentMapper.insert(instrument(user, "AAPL", "US", InvestmentAssetClass.STOCK));

        assertThatThrownBy(() -> instrumentMapper.insert(instrument(user, "AAPL", "US", InvestmentAssetClass.STOCK)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertSqlStateAndConstraint(error, "23505",
                        "uk_investment_instruments_user_market_symbol"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO investment_instruments
                    (user_id, symbol, name, market, asset_class, quote_currency, status)
                VALUES (?, 'aapl', 'Apple', 'US', 'STOCK', 'USD', 'ACTIVE')
                """, user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertSqlStateAndConstraint(error, "23514",
                        "ck_investment_instruments_symbol_canonical"));
    }

    @Test
    void enforcesUserScopedInstrumentBindingAndOneTransactionDrivenPositionIdentity() {
        Long firstUser = insertUser("binding-first");
        Long secondUser = insertUser("binding-second");
        Long firstAccount = insertBrokerageAccount(firstUser, "First brokerage");
        Long secondAccount = insertBrokerageAccount(secondUser, "Second brokerage");
        InvestmentInstrument firstInstrument = instrument(firstUser, "AAPL", "US", InvestmentAssetClass.STOCK);
        instrumentMapper.insert(firstInstrument);

        insertTransactionDrivenProjection(firstUser, firstAccount, firstInstrument.getId(), "First projection");

        assertThatThrownBy(() -> insertTransactionDrivenProjection(firstUser, firstAccount, firstInstrument.getId(), "Duplicate projection"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertSqlStateAndConstraint(error, "23505",
                        "uk_assets_transaction_driven_user_account_instrument"));
        assertThatThrownBy(() -> insertTransactionDrivenProjection(secondUser, secondAccount, firstInstrument.getId(), "Cross user projection"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertSqlStateAndConstraint(error, "23503", "fk_assets_user_instrument"));
    }

    @Test
    void mapsConcurrentInstrumentUniquenessToOneSuccessAndOneConflict() throws Exception {
        Long user = insertUser("instrument-concurrent");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> createConcurrently(user, start));
            Future<Integer> second = executor.submit(() -> createConcurrently(user, start));
            start.countDown();

            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM investment_instruments
                    WHERE user_id = ? AND market = 'US' AND symbol = 'AAPL'
                    """, Integer.class, user)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private int createConcurrently(Long userId, CountDownLatch start) throws Exception {
        start.await();
        try {
            commandService.create(userId,
                    new CreateInvestmentInstrumentCommand("AAPL", "Apple", "US", "STOCK", "USD"));
            return 201;
        } catch (com.financeos.common.BusinessException exception) {
            return exception.getCode();
        }
    }

    private InvestmentInstrument instrument(Long userId, String symbol, String market, InvestmentAssetClass assetClass) {
        InvestmentInstrument instrument = new InvestmentInstrument();
        instrument.setUserId(userId);
        instrument.setSymbol(symbol);
        instrument.setName(symbol + " instrument");
        instrument.setMarket(market);
        instrument.setAssetClass(assetClass);
        instrument.setQuoteCurrency("USD");
        instrument.setStatus(InstrumentStatus.ACTIVE);
        return instrument;
    }

    private Long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES (?, ?, 'hash') RETURNING id
                """, Long.class, username, username + "@example.com");
    }

    private Long insertBrokerageAccount(Long userId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO accounts (user_id, name, type, currency, balance)
                VALUES (?, ?, 'BROKERAGE', 'CNY', 0) RETURNING id
                """, Long.class, userId, name);
    }

    private void insertTransactionDrivenProjection(Long userId, Long accountId, Long instrumentId, String name) {
        jdbcTemplate.update("""
                INSERT INTO assets
                    (user_id, account_id, instrument_id, name, symbol, type, market, currency,
                     quantity, avg_cost, total_cost, realized_profit_loss, position_status,
                     projection_version, position_mode)
                VALUES (?, ?, ?, ?, 'AAPL', 'STOCK', 'US', 'CNY',
                        0, 0, 0, 0, 'CLOSED', 0, 'TRANSACTION_DRIVEN')
                """, userId, accountId, instrumentId, name);
    }

    private void assertSqlStateAndConstraint(Throwable error, String sqlState, String constraintName) {
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(error);
        assertThat(rootCause).isInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) rootCause;
        assertThat(sqlException.getSQLState()).isEqualTo(sqlState);
        assertThat(sqlException.getMessage()).contains(constraintName);
    }
}
