package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.idempotency.application.IdempotencyCommandResult;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.application.RequestFingerprint;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.idempotency.infrastructure.IdempotencyCacheKey;
import com.corebank.idempotency.infrastructure.IdempotencyRecordRepository;
import com.corebank.ledger.infrastructure.JournalEntryRepository;
import com.corebank.ledger.infrastructure.LedgerEntryRepository;
import com.corebank.transaction.infrastructure.AccountTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyRedisIT extends RedisPostgresIntegrationSupport {

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redisCache;
    @Autowired ObjectMapper mapper;
    @Autowired CustomerRepository customers;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accounts;
    @Autowired AccountTransactionRepository transactions;
    @Autowired JournalEntryRepository journals;
    @Autowired LedgerEntryRepository ledgerEntries;
    @Autowired IdempotencyRecordRepository idempotency;
    @Autowired IdempotencyService service;
    @Autowired RequestFingerprint fingerprints;

    private UUID accountId;

    @BeforeEach
    void setup() {
        Customer customer = customers.saveAndFlush(new Customer("Replay Customer",
                UUID.randomUUID().toString(), "replay." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        accountId = account.getId();
    }

    private String key(String raw) {
        return IdempotencyCacheKey.redisKey("corebank:v1:", raw);
    }

    private String depositBody(String amount) {
        return "{\"amount\":" + amount + ",\"description\":\"Salary\"}";
    }

    @Test
    void completedDepositCachesPointerNotFinancialBodyAndReplaysExactlyOnce() throws Exception {
        String raw = "deposit-" + UUID.randomUUID();
        long beforeTx = transactions.count();
        long beforeJournal = journals.count();
        long beforeLedger = ledgerEntries.count();
        long beforeIdempotency = idempotency.count();

        var first = mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("100.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn();

        String redisKey = key(raw);
        String stored = redisCache.opsForValue().get(redisKey);
        assertThat(stored).isNotNull();
        assertThat(redisKey).doesNotContain(raw);
        JsonNode pointer = mapper.readTree(stored);
        assertThat(pointer.path("recordId").asText()).isNotBlank();
        assertThat(pointer.path("operation").asText()).isEqualTo("DEPOSIT");
        assertThat(pointer.path("requestHash").asText()).matches("[0-9a-f]{64}");
        assertThat(pointer.path("httpStatus").asInt()).isEqualTo(201);
        assertThat(pointer.size()).isEqualTo(5);
        assertThat(stored).doesNotContain("balanceBefore", "balanceAfter", "balance",
                "responseBody", "description", "Salary", raw);
        assertThat(redisCache.getExpire(redisKey)).isBetween(1L, 3600L);

        var second = mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("100.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(accounts.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
        assertThat(transactions.count() - beforeTx).isEqualTo(1);
        assertThat(journals.count() - beforeJournal).isEqualTo(1);
        assertThat(ledgerEntries.count() - beforeLedger).isEqualTo(2);
        assertThat(idempotency.count() - beforeIdempotency).isEqualTo(1);
    }

    @Test
    void fullCacheLossMalformedAndStalePointerAllRecoverViaPostgres() throws Exception {
        String raw = "deposit-" + UUID.randomUUID();
        String redisKey = key(raw);
        mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("20.00")))
                .andExpect(status().isCreated());
        long count = transactions.count();

        redisCache.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
        mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("20.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(redisCache.opsForValue().get(redisKey)).isNotNull();

        redisCache.opsForValue().set(redisKey, "{broken");
        mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("20.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        ObjectNode stale = (ObjectNode) mapper.readTree(redisCache.opsForValue().get(redisKey));
        stale.put("recordId", UUID.randomUUID().toString());
        redisCache.opsForValue().set(redisKey, mapper.writeValueAsString(stale));
        mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("20.00")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(transactions.count()).isEqualTo(count);
        assertThat(accounts.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
    }

    @Test
    void mismatchedPayloadIsConflictEvenWithRedisHit() throws Exception {
        String raw = "deposit-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("25.00")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", raw)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("30.00")))
                .andExpect(status().isConflict());
        assertThat(accounts.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("25.00");
    }

    @Test
    void pointerIsInvisibleBeforeCommitAndAbsentAfterRollback() {
        String completedKey = "aftercommit-" + UUID.randomUUID();
        String cacheKey = key(completedKey);
        AtomicBoolean visibleInsideTransaction = new AtomicBoolean(true);

        UUID resourceId = UUID.randomUUID();
        service.execute(completedKey, IdempotencyOperation.DEPOSIT, "a".repeat(64),
                TestReply.class, () -> {
                    visibleInsideTransaction.set(Boolean.TRUE.equals(redisCache.hasKey(cacheKey)));
                    return new IdempotencyCommandResult<>(resourceId, 201, new TestReply(resourceId, "ok"));
                });
        assertThat(visibleInsideTransaction).isFalse();
        assertThat(redisCache.opsForValue().get(cacheKey)).isNotNull();

        String failedKey = "afterrollback-" + UUID.randomUUID();
        assertThatThrownBy(() -> service.execute(
                failedKey, IdempotencyOperation.WITHDRAWAL, "b".repeat(64), TestReply.class,
                () -> { throw new IllegalStateException("forced transactional rollback"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(redisCache.hasKey(key(failedKey))).isFalse();
        assertThat(idempotency.findByIdempotencyKey(failedKey)).isEmpty();
    }

    private record TestReply(UUID id, String outcome) {}
}
