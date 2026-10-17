package com.corebank.shared.exception;

import com.corebank.account.domain.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test void insufficientFunds_shouldReturn409ProblemDetail() {
        ResponseEntity<?> response = handler.handleConflict(new InsufficientBalanceException());
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().toString()).contains("Insufficient balance");
    }

    @Test void blockedOrClosedAccount_shouldReturn409ProblemDetail() {
        ResponseEntity<?> response = handler.handleConflict(new InvalidAccountStateException("BLOCKED"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().toString()).contains("BLOCKED");
    }

    @Test void optimisticLockConflict_shouldReturn409WithoutImplementationDetails() {
        ObjectOptimisticLockingFailureException exception = new ObjectOptimisticLockingFailureException(Account.class, UUID.randomUUID());
        ResponseEntity<?> response = handler.handleOptimisticLock(exception);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().toString()).contains("concurrently").doesNotContain("Hibernate").doesNotContain("ObjectOptimisticLockingFailureException");
    }
}
