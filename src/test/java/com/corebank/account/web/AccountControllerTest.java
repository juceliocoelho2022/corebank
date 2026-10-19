package com.corebank.account.web;

import com.corebank.account.application.AccountService;
import com.corebank.account.application.AccountSummary;
import com.corebank.account.application.AccountSummaryService;
import com.corebank.account.domain.AccountStatus;
import java.time.Instant;
import com.corebank.account.domain.Account;
import com.corebank.shared.exception.ApiExceptionHandler;
import com.corebank.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import(ApiExceptionHandler.class)
class AccountControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountService service;

    @MockitoBean
    AccountSummaryService summaryService;

    @Test
    void createsAccount() throws Exception {
        UUID customerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID accountId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Account account = new Account(customerId, "10000001");
        forceId(account, accountId);
        when(service.create(customerId)).thenReturn(account);

        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"11111111-1111-1111-1111-111111111111"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.branch").value("0001"))
                .andExpect(jsonPath("$.number").value("10000001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void getsAccount() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Account account = new Account(customerId, "10000001");
        forceId(account, accountId);
        when(service.get(accountId)).thenReturn(account);

        mvc.perform(get("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()));
    }

    @Test
    void getsBalance() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Account account = new Account(customerId, "10000001");
        forceId(account, accountId);
        when(service.get(accountId)).thenReturn(account);

        mvc.perform(get("/api/v1/accounts/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.currency").value("BRL"));
    }

    @Test
    void returnsNotFoundWhenCustomerDoesNotExist() throws Exception {
        UUID customerId = UUID.randomUUID();
        when(service.create(customerId)).thenThrow(new ResourceNotFoundException("Customer not found"));

        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void rejectsMissingCustomerId() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void rejectsInvalidCustomerId() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void returnsBadRequestForMalformedAccountUuid() throws Exception {
        mvc.perform(get("/api/v1/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void returnsNotFoundForMissingAccount() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(service.get(accountId)).thenThrow(new ResourceNotFoundException("Account not found"));

        mvc.perform(get("/api/v1/accounts/{id}", accountId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account not found"));
    }


    @Test
    void getsAccountSummaryWithoutBalance() throws Exception {
        UUID id = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(summaryService.get(id)).thenReturn(new AccountSummary(id, customerId,
                "0001", "10000001", AccountStatus.ACTIVE, "BRL", Instant.parse("2026-10-17T12:00:00Z")));

        mvc.perform(get("/api/v1/accounts/{id}/summary", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.currency").value("BRL"))
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist());
    }

    private static void forceId(Account account, UUID id) throws Exception {
        var field = Account.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(account, id);
    }
}
