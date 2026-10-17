package com.corebank.reconciliation.web;

import com.corebank.reconciliation.application.ReconciliationResult;
import com.corebank.reconciliation.application.ReconciliationService;
import com.corebank.reconciliation.application.ReconciliationStatus;
import com.corebank.shared.exception.ApiExceptionHandler;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReconciliationController.class)
@Import(ApiExceptionHandler.class)
class ReconciliationControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ReconciliationService service;

    @Test
    void reconcile_shouldReturn200AndBalances() throws Exception {
        UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.reconcile(accountId)).thenReturn(new ReconciliationResult(
                accountId,
                new BigDecimal("850.00"),
                new BigDecimal("850.00"),
                new BigDecimal("0.00"),
                ReconciliationStatus.RECONCILED));

        mvc.perform(get("/api/v1/reconciliation/accounts/{accountId}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.operationalBalance").value(850.00))
                .andExpect(jsonPath("$.ledgerBalance").value(850.00))
                .andExpect(jsonPath("$.difference").value(0.00))
                .andExpect(jsonPath("$.status").value("RECONCILED"));
    }

    @Test
    void missingAccount_shouldReturn404() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(service.reconcile(accountId)).thenThrow(new ResourceNotFoundException("Account not found"));

        mvc.perform(get("/api/v1/reconciliation/accounts/{accountId}", accountId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found"));
    }

    @Test
    void malformedAccountId_shouldReturn400() throws Exception {
        mvc.perform(get("/api/v1/reconciliation/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
