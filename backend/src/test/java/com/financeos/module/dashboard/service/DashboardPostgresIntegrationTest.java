package com.financeos.module.dashboard.service;

import com.financeos.integration.PostgresIntegrationTest;
import com.financeos.module.dashboard.dto.DashboardDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@Import(DashboardPostgresIntegrationTest.FixedClockConfiguration.class)
class DashboardPostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private DashboardService dashboardService;

    @Test
    void fillsSixMonthsWithZeroWhenPostgresQueryReturnsNoRows() {
        Long userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (username, email, password_hash)
                VALUES ('empty-dashboard-user', 'empty-dashboard-user@example.com', 'hash')
                RETURNING id
                """, Long.class);

        DashboardDto dashboard = dashboardService.getDashboard(userId);

        assertThat(dashboard.monthlyCashFlowTrend()).extracting(DashboardDto.MonthlyCashFlow::month)
                .containsExactly("2025-10", "2025-11", "2025-12", "2026-01", "2026-02", "2026-03");
        assertThat(dashboard.monthlyCashFlowTrend()).allSatisfy(month -> {
            assertThat(month.income()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(month.expense()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(month.net()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-03-15T04:30:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }
}
