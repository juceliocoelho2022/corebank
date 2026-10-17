package com.corebank.transfer.web;

import com.corebank.idempotency.application.IdempotencyCommandResult;
import com.corebank.idempotency.application.IdempotencyExecution;
import com.corebank.idempotency.application.IdempotencyKeyValidator;
import com.corebank.idempotency.application.IdempotencyService;
import com.corebank.idempotency.application.RequestFingerprint;
import com.corebank.idempotency.domain.IdempotencyOperation;
import com.corebank.transfer.application.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final TransferService service;
    private final IdempotencyKeyValidator keyValidator;
    private final RequestFingerprint fingerprint;
    private final IdempotencyService idempotencyService;

    public TransferController(
            TransferService service,
            IdempotencyKeyValidator keyValidator,
            RequestFingerprint fingerprint,
            IdempotencyService idempotencyService) {
        this.service = service;
        this.keyValidator = keyValidator;
        this.fingerprint = fingerprint;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        String key = keyValidator.validate(idempotencyKey);
        String requestHash = fingerprint.transfer(
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.description());

        IdempotencyExecution<TransferResponse> execution = idempotencyService.execute(
                key,
                IdempotencyOperation.TRANSFER,
                requestHash,
                TransferResponse.class,
                () -> {
                    TransferResponse response = TransferResponse.from(service.transfer(
                            request.sourceAccountId(),
                            request.destinationAccountId(),
                            request.amount(),
                            request.description()));
                    return new IdempotencyCommandResult<>(response.transferId(), HttpStatus.CREATED.value(), response);
                });

        return ResponseEntity.status(execution.httpStatus())
                .header(REPLAY_HEADER, Boolean.toString(execution.replayed()))
                .body(execution.body());
    }
}
