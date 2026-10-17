package com.corebank.transaction.web;

import com.corebank.idempotency.application.IdempotencyCommandResult;
import com.corebank.idempotency.application.IdempotencyExecution;
import com.corebank.idempotency.application.IdempotencyKeyValidator;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.application.RequestFingerprint;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.shared.exception.ApiExceptionHandler;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transaction.application.TransactionService;
import com.corebank.transaction.domain.AccountTransaction;
import com.corebank.transaction.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
@Import({ApiExceptionHandler.class, IdempotencyKeyValidator.class, RequestFingerprint.class})
class TransactionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TransactionService service;
    @MockitoBean IdempotencyService idempotencyService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void executeIdempotentCommand() {
        when(idempotencyService.execute(
                anyString(),
                any(IdempotencyOperation.class),
                anyString(),
                eq(TransactionResponse.class),
                any()))
                .thenAnswer(invocation -> {
                    Supplier<IdempotencyCommandResult<TransactionResponse>> command = invocation.getArgument(4);
                    IdempotencyCommandResult<TransactionResponse> result = command.get();
                    return new IdempotencyExecution<>(result.body(), result.httpStatus(), false);
                });
    }

    @Test
    void deposit_shouldReturn201AndMovementSnapshot() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AccountTransaction tx = transaction(id, TransactionType.DEPOSIT, "100.00", "0.00", "100.00", "Initial deposit");
        when(service.deposit(eq(id), eq(new BigDecimal("100.00")), eq("Initial deposit"))).thenReturn(tx);

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", id)
                        .header("Idempotency-Key", "deposit-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.00,\"description\":\"Initial deposit\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.accountId").value(id.toString()))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.balanceBefore").value(0.00))
                .andExpect(jsonPath("$.balanceAfter").value(100.00));
    }

    @Test
    void withdrawal_shouldReturn201AndMovementSnapshot() throws Exception {
        UUID id = UUID.randomUUID();
        AccountTransaction tx = transaction(id, TransactionType.WITHDRAWAL, "30.00", "100.00", "70.00", "ATM");
        when(service.withdraw(eq(id), eq(new BigDecimal("30.00")), eq("ATM"))).thenReturn(tx);

        mvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", id)
                        .header("Idempotency-Key", "withdraw-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30.00,\"description\":\"ATM\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.balanceAfter").value(70.00));
    }

    @Test
    void missingIdempotencyKey_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key header is required"));
    }

    @Test
    void invalidAmount_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", UUID.randomUUID())
                        .header("Idempotency-Key", "deposit-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void amountWithMoreThanTwoDecimals_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", UUID.randomUUID())
                        .header("Idempotency-Key", "deposit-decimals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.001}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void descriptionLongerThan200_shouldReturn400() throws Exception {
        String d = "x".repeat(201);
        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", UUID.randomUUID())
                        .header("Idempotency-Key", "deposit-description")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"description\":\"" + d + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void whitespaceDescription_shouldBeNormalizedBeforeValidation() throws Exception {
        UUID id = UUID.randomUUID();
        AccountTransaction tx = transaction(id, TransactionType.DEPOSIT, "10.00", "0.00", "10.00", null);
        when(service.deposit(eq(id), eq(new BigDecimal("10.00")), eq(null))).thenReturn(tx);

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", id)
                        .header("Idempotency-Key", "deposit-whitespace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"description\":\"" + " ".repeat(205) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").doesNotExist());
    }

    @Test
    void paddedDescription_shouldValidateTrimmedLength() throws Exception {
        UUID id = UUID.randomUUID();
        String trimmed = "x".repeat(200);
        AccountTransaction tx = transaction(id, TransactionType.DEPOSIT, "10.00", "0.00", "10.00", trimmed);
        when(service.deposit(eq(id), eq(new BigDecimal("10.00")), eq(trimmed))).thenReturn(tx);

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", id)
                        .header("Idempotency-Key", "deposit-padded")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"description\":\"  " + trimmed + "  \"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void malformedAccountId_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/accounts/not-a-uuid/deposits")
                        .header("Idempotency-Key", "deposit-malformed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAccount_shouldReturn404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.deposit(eq(id), any(BigDecimal.class), any()))
                .thenThrow(new ResourceNotFoundException("Account not found"));

        mvc.perform(post("/api/v1/accounts/{accountId}/deposits", id)
                        .header("Idempotency-Key", "deposit-missing-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found"));
    }

    @Test
    void insufficientBalance_shouldReturn409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.withdraw(eq(id), any(BigDecimal.class), any()))
                .thenThrow(new InsufficientBalanceException());

        mvc.perform(post("/api/v1/accounts/{accountId}/withdrawals", id)
                        .header("Idempotency-Key", "withdraw-insufficient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":80.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Insufficient balance"));
    }

    @Test
    void history_shouldReturnNewestFirst() throws Exception {
        UUID id = UUID.randomUUID();
        AccountTransaction newest = transaction(id, TransactionType.WITHDRAWAL, "20.00", "100.00", "80.00", null);
        AccountTransaction oldest = transaction(id, TransactionType.DEPOSIT, "100.00", "0.00", "100.00", null);
        ReflectionTestUtils.setField(newest, "createdAt", Instant.parse("2026-10-17T12:01:00Z"));
        ReflectionTestUtils.setField(oldest, "createdAt", Instant.parse("2026-10-17T12:00:00Z"));
        when(service.listByAccount(id)).thenReturn(List.of(newest, oldest));

        mvc.perform(get("/api/v1/accounts/{accountId}/transactions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$[1].type").value("DEPOSIT"));
    }

    private static AccountTransaction transaction(
            UUID id,
            TransactionType type,
            String amount,
            String before,
            String after,
            String description) {
        AccountTransaction tx = new AccountTransaction(
                id, type, new BigDecimal(amount), new BigDecimal(before), new BigDecimal(after), description);
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(tx, "createdAt", Instant.parse("2026-10-17T12:00:00Z"));
        return tx;
    }
}
