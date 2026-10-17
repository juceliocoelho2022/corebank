package com.corebank.transfer.web;

import com.corebank.shared.exception.ApiExceptionHandler;
import com.corebank.shared.exception.InsufficientBalanceException;
import com.corebank.shared.exception.ResourceNotFoundException;
import com.corebank.transfer.application.TransferResult;
import com.corebank.transfer.application.TransferService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import(ApiExceptionHandler.class)
class TransferControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TransferService service;

    @Test
    void transfer_shouldReturn201() throws Exception {
        UUID transferId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        when(service.transfer(eq(sourceId), eq(destinationId), eq(new BigDecimal("150.00")), eq("Payment")))
                .thenReturn(new TransferResult(transferId, sourceId, destinationId, new BigDecimal("150.00"),
                        new BigDecimal("850.00"), new BigDecimal("350.00"), Instant.parse("2026-10-17T12:00:00Z")));

        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":150.00,"description":"Payment"}
                                """.formatted(sourceId, destinationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.sourceAccountId").value(sourceId.toString()))
                .andExpect(jsonPath("$.destinationAccountId").value(destinationId.toString()))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.sourceBalanceAfter").value(850.00))
                .andExpect(jsonPath("$.destinationBalanceAfter").value(350.00));
    }

    @Test
    void invalidPayload_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/transfers")
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":10.00}".formatted(sourceId, destinationId)))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedJson_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest());
    }
}
