package com.financeos.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.module.ledger.service.TransactionWriteRules;
import com.financeos.module.user.dto.LoginRequest;
import com.financeos.module.user.dto.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/** Real socket HTTP + Jackson + PostgreSQL; no MockMvc or double-valued assertions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionAmountRuntimeIntegrationTest extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/transactions";
    private static final List<String> AMOUNTS = List.of("0.01", "1.00", "1234.56", "99999999999999.99",
            "100000000000000.01", "100000000000000.02", "100000000000000.03", "9999999999999999.99");

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper objectMapper;
    @SpyBean private TransactionWriteRules rules;
    @LocalServerPort private int port;

    @Test
    void decimalStringMatrixBindsExactlyAndSurvivesCreateRepeatedUpdateAndDeleteForEveryEffect() throws Exception {
        User user = user();
        for (String type : List.of("INCOME", "EXPENSE", "ADJUSTMENT")) {
            for (boolean negative : "ADJUSTMENT".equals(type) ? List.of(false, true) : List.of(false)) {
                for (int index = 0; index < AMOUNTS.size(); index++) {
                    long account = account(user, "0.00");
                    long category = category(user, type);
                    String amount = signed(AMOUNTS.get(index), negative);
                    String updated = signed(AMOUNTS.get((index + 1) % AMOUNTS.size()), negative);
                    long id = write(user, HttpMethod.POST, PATH, account, category, type, amount).at("/data/id").asLong();
                    assertState(user, id, account, type, amount);
                    write(user, HttpMethod.PUT, PATH + "/" + id, account, category, type, updated);
                    assertState(user, id, account, type, updated);
                    write(user, HttpMethod.PUT, PATH + "/" + id, account, category, type, amount);
                    assertState(user, id, account, type, amount);
                    assertStatus(exchange(user, HttpMethod.DELETE, PATH + "/" + id, null), 200);
                    assertBalance(user, account, "0.00");
                    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE id = ?", Integer.class, id)).isZero();
                    assertStatus(exchange(user, HttpMethod.GET, PATH + "/" + id, null), 404);
                }
            }
        }
        assertThat(jdbcTemplate.queryForList("SELECT numeric_precision FROM information_schema.columns WHERE table_schema = 'public' AND (table_name, column_name) IN (('transactions', 'amount'), ('accounts', 'balance'))", Integer.class))
                .containsExactlyInAnyOrder(18, 18);
        assertThat(jdbcTemplate.queryForList("SELECT numeric_scale FROM information_schema.columns WHERE table_schema = 'public' AND (table_name, column_name) IN (('transactions', 'amount'), ('accounts', 'balance'))", Integer.class))
                .containsExactlyInAnyOrder(2, 2);
    }

    @Test
    void existingJsonNumbersAndExactDecimalNotationRemainCompatible() throws Exception {
        User user = user();
        long account = account(user, "0.00");
        long category = category(user, "INCOME");
        String numberBody = body(account, category, "INCOME", "100000000000000.01");
        long id = assertStatus(exchange(user, HttpMethod.POST, PATH, numberBody), 200).at("/data/id").asLong();
        assertState(user, id, account, "INCOME", "100000000000000.01");
        for (String text : List.of("1", "1.0", "+1.00", "0001.00", ".01", "1.", "1e2", "1e-2", "10000000000000001e-2", "1.000e1")) {
            write(user, HttpMethod.PUT, PATH + "/" + id, account, category, "INCOME", text);
            assertState(user, id, account, "INCOME", new BigDecimal(text).setScale(2).toPlainString());
        }
        assertStatus(exchange(user, HttpMethod.DELETE, PATH + "/" + id, null), 200);
        assertBalance(user, account, "0.00");
    }

    @Test
    void invalidAmountsRejectCreateAndUpdateWithoutChangingFactOrBalance() throws Exception {
        User user = user();
        for (String type : List.of("INCOME", "EXPENSE", "ADJUSTMENT")) {
            long account = account(user, "0.00");
            long category = category(user, type);
            long id = write(user, HttpMethod.POST, PATH, account, category, type, "0.01").at("/data/id").asLong();
            List<String> invalid = new java.util.ArrayList<>(List.of("null", "\"\"", "\"abc\"", "\"1.2.3\"", "\"1.001\"",
                    "\"1.000\"", "\"10000000000000000.00\"", "\"1e16\"", "\"1e-3\"", "\"0\"", "\"-0.00\""));
            if (!"ADJUSTMENT".equals(type)) invalid.add("\"-0.01\"");
            for (String token : invalid) {
                String requestBody = body(account, category, type, token);
                assertStatus(exchange(user, HttpMethod.POST, PATH, requestBody), 400);
                assertStatus(exchange(user, HttpMethod.PUT, PATH + "/" + id, requestBody), 400);
                assertState(user, id, account, type, "0.01");
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE account_id = ?", Integer.class, account)).isEqualTo(1);
            }
            String missing = body(account, category, type, "null").replace("\"amount\":null,", "");
            assertStatus(exchange(user, HttpMethod.POST, PATH, missing), 400);
            assertStatus(exchange(user, HttpMethod.PUT, PATH + "/" + id, missing), 400);
            assertState(user, id, account, type, "0.01");
        }
    }

    @Test
    void numericBalanceOverflowRollsBackCreateAndUpdateAtomically() throws Exception {
        User user = user();
        long category = category(user, "INCOME");
        long full = account(user, "9999999999999999.99");
        assertStatus(exchange(user, HttpMethod.POST, PATH, body(full, category, "INCOME", "\"0.01\"")), 400);
        assertBalance(user, full, "9999999999999999.99");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions WHERE account_id = ?", Integer.class, full)).isZero();

        long account = account(user, "9999999999999999.98");
        long id = write(user, HttpMethod.POST, PATH, account, category, "INCOME", "0.01").at("/data/id").asLong();
        assertStatus(exchange(user, HttpMethod.PUT, PATH + "/" + id, body(account, category, "INCOME", "\"0.02\"")), 400);
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo("0.01");
        assertBalance(user, account, "9999999999999999.99");
        assertStatus(exchange(user, HttpMethod.DELETE, PATH + "/" + id, null), 200);
        assertBalance(user, account, "9999999999999999.98");
    }

    @Test
    void exactAmountsRespectAccountAndCrossUserIsolation() throws Exception {
        User owner = user();
        User other = user();
        long ownedAccount = account(owner, "0.00");
        long otherAccount = account(other, "0.00");
        long category = category(owner, "INCOME");
        long otherCategory = category(other, "INCOME");
        long id = write(owner, HttpMethod.POST, PATH, ownedAccount, category, "INCOME", "100000000000000.01").at("/data/id").asLong();
        assertStatus(exchange(other, HttpMethod.GET, PATH + "/" + id, null), 404);
        assertStatus(exchange(other, HttpMethod.PUT, PATH + "/" + id, body(otherAccount, otherCategory, "INCOME", "\"100000000000000.02\"")), 404);
        assertStatus(exchange(other, HttpMethod.DELETE, PATH + "/" + id, null), 404);
        assertStatus(exchange(owner, HttpMethod.POST, PATH, body(otherAccount, category, "INCOME", "\"100000000000000.01\"")), 404);
        assertStatus(exchange(owner, HttpMethod.POST, PATH, body(ownedAccount, otherCategory, "INCOME", "\"100000000000000.01\"")), 404);
        assertStatus(exchange(owner, HttpMethod.PUT, PATH + "/" + id, body(otherAccount, category, "INCOME", "\"100000000000000.02\"")), 404);
        JsonNode page = assertStatus(exchange(other, HttpMethod.GET, PATH + "/page", null), 200);
        assertThat(page.at("/data/records").isEmpty()).isTrue();
        assertState(owner, id, ownedAccount, "INCOME", "100000000000000.01");
        assertBalance(other, otherAccount, "0.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transactions", Integer.class)).isEqualTo(1);
    }

    private JsonNode write(User user, HttpMethod method, String path, long account, long category, String type, String amount) throws Exception {
        String requestBody = body(account, category, type, objectMapper.writeValueAsString(amount));
        assertThat(requestBody).contains("\"amount\":\"" + amount + "\"");
        clearInvocations(rules);
        ResponseEntity<String> response = exchange(user, method, path, requestBody);
        JsonNode json = assertStatus(response, 200);
        ArgumentCaptor<BigDecimal> captured = ArgumentCaptor.forClass(BigDecimal.class);
        verify(rules).validateAmount(eq(type), captured.capture(), eq("C7 precision"));
        assertThat(captured.getValue()).isEqualTo(new BigDecimal(amount));
        assertThat(json.at("/data/amount").decimalValue()).isEqualByComparingTo(amount);
        if (method == HttpMethod.POST && "INCOME".equals(type) && "100000000000000.01".equals(amount)) {
            long id = json.at("/data/id").asLong();
            System.out.printf("C7_EVIDENCE real HTTP request=%s; bound BigDecimal=%s; PostgreSQL amount=%s; PostgreSQL balance=%s; HTTP response amount=%s%n",
                    requestBody, captured.getValue().toPlainString(),
                    jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, id).toPlainString(),
                    jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account).toPlainString(),
                    json.at("/data/amount").decimalValue().toPlainString());
        }
        return json;
    }

    private void assertState(User user, long id, long account, String type, String amount) throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT amount FROM transactions WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo(amount);
        assertThat(jdbcTemplate.queryForObject("SELECT type FROM transactions WHERE id = ?", String.class, id)).isEqualTo(type);
        JsonNode detail = assertStatus(exchange(user, HttpMethod.GET, PATH + "/" + id, null), 200);
        assertThat(detail.at("/data/amount").isNumber()).isTrue();
        assertThat(detail.at("/data/amount").decimalValue()).isEqualByComparingTo(amount);
        JsonNode page = assertStatus(exchange(user, HttpMethod.GET, PATH + "/page?accountId=" + account, null), 200);
        assertThat(page.at("/data/records").size()).isEqualTo(1);
        assertThat(page.at("/data/records/0/amount").decimalValue()).isEqualByComparingTo(amount);
        assertBalance(user, account, "EXPENSE".equals(type) ? new BigDecimal(amount).negate().toPlainString() : amount);
    }

    private void assertBalance(User user, long account, String expected) throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, account)).isEqualByComparingTo(expected);
        JsonNode api = assertStatus(exchange(user, HttpMethod.GET, "/api/v1/accounts/" + account, null), 200);
        assertThat(api.at("/data/balance").decimalValue()).isEqualByComparingTo(expected);
    }

    private JsonNode assertStatus(ResponseEntity<String> response, int expected) throws Exception {
        assertThat(response.getStatusCode().value()).withFailMessage("HTTP status %s: %s", response.getStatusCode(), response.getBody()).isEqualTo(expected);
        JsonNode body = objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(expected);
        return body;
    }

    private ResponseEntity<String> exchange(User user, HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) headers.setBearerAuth(user.token());
        return http.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String body(long account, long category, String type, String amountToken) {
        return "{\"accountId\":%d,\"categoryId\":%d,\"type\":\"%s\",\"amount\":%s,\"currency\":\"CNY\",\"description\":\"C7 precision\",\"transactedAt\":\"2026-10-10T10:00:00\"}"
                .formatted(account, category, type, amountToken);
    }

    private User user() throws Exception {
        String username = "c7_" + UUID.randomUUID().toString().replace("-", "");
        assertStatus(exchange(null, HttpMethod.POST, "/api/v1/register", objectMapper.writeValueAsString(new RegisterRequest(username, username + "@example.test", "password123"))), 200);
        JsonNode login = assertStatus(exchange(null, HttpMethod.POST, "/api/v1/login", objectMapper.writeValueAsString(new LoginRequest(username, "password123"))), 200);
        return new User(login.at("/data/userId").asLong(), login.at("/data/token").asText());
    }

    private long account(User user, String balance) {
        return jdbcTemplate.queryForObject("INSERT INTO accounts (user_id, name, type, currency, balance, status) VALUES (?, 'C7', 'CASH', 'CNY', ?, 'ACTIVE') RETURNING id", Long.class, user.id(), new BigDecimal(balance));
    }

    private long category(User user, String type) {
        return jdbcTemplate.queryForObject("INSERT INTO categories (user_id, name, type, is_system) VALUES (?, ?, ?, false) RETURNING id", Long.class, user.id(), "C7-" + UUID.randomUUID(), "ADJUSTMENT".equals(type) ? "INCOME" : type);
    }

    private String signed(String amount, boolean negative) { return negative ? "-" + amount : amount; }
    private record User(long id, String token) { }
}
