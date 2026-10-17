package com.corebank.integration;

import com.corebank.account.application.AccountService;
import com.corebank.account.domain.Account;
import com.corebank.account.infrastructure.AccountRepository;
import com.corebank.customer.domain.Customer;
import com.corebank.customer.infrastructure.CustomerRepository;
import com.corebank.event.domain.OutboxStatus;
import com.corebank.event.infrastructure.DeadLetterEventRepository;
import com.corebank.event.infrastructure.OutboxEventRepository;
import com.corebank.event.infrastructure.OutboxPublisher;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OutboxKafkaFailureIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired DeadLetterEventRepository deadLetterRepository;
    @Autowired OutboxPublisher publisher;

    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        deadLetterRepository.deleteAll();
        outboxRepository.deleteAll();
        Customer customer = customerRepository.saveAndFlush(new Customer(
                "Kafka Failure Customer",
                UUID.randomUUID().toString(),
                "kafka.failure." + UUID.randomUUID() + "@example.com"));
        Account account = accountService.create(customer.getId());
        accountId = account.getId();
    }

    @Test
    void brokerFailureAfterFinancialCommit_shouldKeepFinanceCommittedAndScheduleRetry() throws Exception {
        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", accountId)
                        .header("Idempotency-Key", "kafka-failure-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00}"))
                .andExpect(status().isCreated());

        var event = outboxRepository.findAll().stream().findFirst().orElseThrow();
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        CompletableFuture failed = new CompletableFuture();
        failed.completeExceptionally(new TimeoutException("broker unavailable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        assertThat(publisher.publishPendingBatch()).isEqualTo(1);

        var retried = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(accountRepository.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(retried.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retried.getAttemptCount()).isEqualTo(1);
        assertThat(retried.getLastErrorType()).isEqualTo("TimeoutException");
        assertThat(retried.getLastErrorMessage()).isEqualTo("Transient Kafka publication failure");
        assertThat(retried.getPublishedAt()).isNull();
        assertThat(deadLetterRepository.count()).isZero();
    }
}
