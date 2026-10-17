package com.corebank.transaction.web;

import com.corebank.idempotency.application.IdempotencyCommandResult;
import com.corebank.idempotency.application.IdempotencyExecution;
import com.corebank.idempotency.application.IdempotencyKeyValidator;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.application.RequestFingerprint;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.transaction.application.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class TransactionController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final TransactionService service;
    private final IdempotencyKeyValidator keyValidator;
    private final RequestFingerprint fingerprint;
    private final IdempotencyService idempotencyService;

    public TransactionController(
            TransactionService service,
            IdempotencyKeyValidator keyValidator,
            RequestFingerprint fingerprint,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.keyValidator = keyValidator;
        this.fingerprint = fingerprint;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/deposits")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID accountId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request) {

        String key = keyValidator.validate(idempotencyKey);
        String requestHash = fingerprint.deposit(accountId, request.amount(), request.description());
        IdempotencyExecution<TransactionResponse> execution = idempotencyService.execute(
                key,
                IdempotencyOperation.DEPOSIT,
                requestHash,
                TransactionResponse.class,
                () -> {
                    TransactionResponse response = TransactionResponse.from(
                            service.deposit(accountId, request.amount(), request.description()));
                    return new IdempotencyCommandResult<>(response.transactionId(), HttpStatus.CREATED.value(), response);
                });

        return response(execution);
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID accountId,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody MoneyMovementRequest request) {

        String key = keyValidator.validate(idempotencyKey);
        String requestHash = fingerprint.withdrawal(accountId, request.amount(), request.description());
        IdempotencyExecution<TransactionResponse> execution = idempotencyService.execute(
                key,
                IdempotencyOperation.WITHDRAWAL,
                requestHash,
                TransactionResponse.class,
                () -> {
                    TransactionResponse response = TransactionResponse.from(
                            service.withdraw(accountId, request.amount(), request.description()));
                    return new IdempotencyCommandResult<>(response.transactionId(), HttpStatus.CREATED.value(), response);
                });

        return response(execution);
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> history(@PathVariable UUID accountId) {
        return service.listByAccount(accountId).stream().map(TransactionResponse::from).toList();
    }

    private ResponseEntity<TransactionResponse> response(IdempotencyExecution<TransactionResponse> execution) {
        return ResponseEntity.status(execution.httpStatus())
                .header(REPLAY_HEADER, Boolean.toString(execution.replayed()))
                .body(execution.body());
    }
}
