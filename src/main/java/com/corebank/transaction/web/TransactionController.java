package com.corebank.transaction.web;

import com.corebank.transaction.application.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class TransactionController {
    private final TransactionService service;
    public TransactionController(TransactionService service) { this.service = service; }

    @PostMapping("/deposits")
    public ResponseEntity<TransactionResponse> deposit(@PathVariable UUID accountId, @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResponse response = TransactionResponse.from(service.deposit(accountId, request.amount(), request.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<TransactionResponse> withdraw(@PathVariable UUID accountId, @Valid @RequestBody MoneyMovementRequest request) {
        TransactionResponse response = TransactionResponse.from(service.withdraw(accountId, request.amount(), request.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> history(@PathVariable UUID accountId) {
        return service.listByAccount(accountId).stream().map(TransactionResponse::from).toList();
    }
}
