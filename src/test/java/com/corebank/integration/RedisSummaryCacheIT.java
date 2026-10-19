package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.transaction.application.TransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class RedisSummaryCacheIT extends RedisPostgresIntegrationSupport {
    @Autowired MockMvc mvc;
    @Autowired CustomerRepository customers;
    @Autowired AccountService accounts;
    @Autowired TransactionService transactions;
    @Autowired StringRedisTemplate cache;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private UUID customerId;
    private UUID accountId;

    @BeforeEach
    void createFixtures() {
        Customer customer = customers.saveAndFlush(new Customer(
                "Redis Customer", UUID.randomUUID().toString(),
                "redis." + UUID.randomUUID() + "@example.com"));
        customerId = customer.getId();
        Account account = accounts.create(customerId);
        accountId = account.getId();
    }

    @Test
    void customerSummaryUsesActualFiveMinuteCacheAndExcludesPii() throws Exception {
        String key = "corebank:v1:customer-summary:" + customerId;
        mvc.perform(get("/api/v1/customers/{id}/summary", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Redis Customer"))
                .andExpect(jsonPath("$.document").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());

        String stored = cache.opsForValue().get(key);
        assertThat(stored).isNotNull();
        JsonNode value = json.readTree(stored);
        assertThat(value.path("id").asText()).isEqualTo(customerId.toString());
        assertThat(value.path("name").asText()).isEqualTo("Redis Customer");
        assertThat(value.path("createdAt").asText()).isNotBlank();
        assertThat(value.has("document")).isFalse();
        assertThat(value.has("email")).isFalse();
        assertThat(cache.getExpire(key)).isBetween(1L, 300L);

        jdbc.update("UPDATE customers SET name = ? WHERE id = ?", "Updated in Postgres", customerId);

        mvc.perform(get("/api/v1/customers/{id}/summary", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Redis Customer"));
        mvc.perform(get("/api/v1/customers/{id}", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated in Postgres"))
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.document").exists());
    }

    @Test
    void accountSummaryRemainsBalanceFreeAfterDeposit() throws Exception {
        String key = "corebank:v1:account-summary:" + accountId;
        mvc.perform(get("/api/v1/accounts/{id}/summary", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());

        String stored = cache.opsForValue().get(key);
        assertThat(stored).isNotNull();
        JsonNode value = json.readTree(stored);
        assertThat(value.path("currency").asText()).isEqualTo("BRL");
        assertThat(value.path("createdAt").asText()).isNotBlank();
        assertThat(value.has("balance")).isFalse();
        assertThat(value.has("version")).isFalse();
        assertThat(value.has("updatedAt")).isFalse();
        assertThat(cache.getExpire(key)).isBetween(1L, 300L);

        transactions.deposit(accountId, new BigDecimal("23.00"), "deposit");
        mvc.perform(get("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(23.00));
        mvc.perform(get("/api/v1/accounts/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(23.00));
        mvc.perform(get("/api/v1/accounts/{id}/summary", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").doesNotExist());
        assertThat(cache.opsForValue().get(key)).isEqualTo(stored);
    }

    @Test
    void malformedCustomerCacheFallsBackToPostgres() throws Exception {
        String key = "corebank:v1:customer-summary:" + customerId;
        cache.opsForValue().set(key, "malformed-json");
        mvc.perform(get("/api/v1/customers/{id}/summary", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Redis Customer"));
        assertThat(json.readTree(cache.opsForValue().get(key)).path("name").asText()).isEqualTo("Redis Customer");
    }
}
