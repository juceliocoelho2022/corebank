package com.corebank.transfer.web;

import com.corebank.idempotency.application.IdempotencyCommandResult;
import com.corebank.idempotency.application.IdempotencyExecution;
import com.corebank.idempotency.application.IdempotencyKeyValidator;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.application.RequestFingerprint;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.shared.exception.ApiExceptionHandler;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transfer.application.TransferResult;
import com.corebank.transfer.application.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import({ApiExceptionHandler.class, IdempotencyKeyValidator.class, RequestFingerprint.class})
class TransferControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TransferService service;
    @MockitoBean IdempotencyService idempotencyService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void executeIdempotentCommand() {
        when(idempotencyService.execute(
                anyString(),
                any(IdempotencyOperation.class),
                anyString(),
                eq(TransferResponse.class),
                any()))
                .thenAnswer(invocation -> {
                    Supplier<IdempotencyCommandResult<TransferResponse>> command = invocation.getArgument(4);
                    IdempotencyCommandResult<TransferResponse> result = command.get();
                    return new IdempotencyExecution<>(result.body(), result.httpStatus(), false);
                });
    }

    @Test
    void transfer_shouldReturn201() throws Exception {
        UUID transferId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(service.transfer(eq(sourceId), eq(destinationId), eq(new BigDecimal("150.00")), eq("Payment")))
                .thenReturn(new TransferResult(transferId, sourceId, destinationId, new BigDecimal("150.00"),
                        new BigDecimal("850.00"), new BigDecimal("350.00"), Instant.parse("2026-10-17T12:00:00Z")));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "transfer-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":150.00,"description":"Payment"}
                                """.formatted(sourceId, destinationId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.sourceAccountId").value(sourceId.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(destinationId.toString()))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.sourceBalanceAfter").value(850.00))
                .andExpect(jsonPath("$.destinationBalanceAfter").value(350.00));
    }

    @Test
    void missingIdempotencyKey_shouldReturn400() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();

        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":10.00}"
                                .formatted(sourceId, destinationId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key header is required"));
    }

    @Test
    void invalidPayload_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "transfer-invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void sameAccount_shouldReturn400() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.transfer(eq(id), eq(id), any(BigDecimal.class), any()))
                .thenThrow(new IllegalArgumentException("Source and destination accounts must be different"));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "transfer-same-account")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":10.00}".formatted(id, id)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAccount_shouldReturn404() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(service.transfer(eq(sourceId), eq(destinationId), any(BigDecimal.class), any()))
                .thenThrow(new ResourceNotFoundException("Destination account not found"));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "transfer-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":10.00}".formatted(sourceId, destinationId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void insufficientBalance_shouldReturn409() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(service.transfer(eq(sourceId), eq(destinationId), any(BigDecimal.class), any()))
                .thenThrow(new InsufficientBalanceException());

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "transfer-balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":80.00}".formatted(sourceId, destinationId)))
                .andExpect(status().isConflict());
    }

    @Test
    void optimisticLock_shouldReturn409() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(service.transfer(eq(sourceId), eq(destinationId), any(BigDecimal.class), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Account", sourceId));

        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "transfer-lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":10.00}".formatted(sourceId, destinationId)))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedJson_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "transfer-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest());
    }
}
