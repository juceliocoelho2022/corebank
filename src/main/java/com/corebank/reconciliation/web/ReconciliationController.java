package com.corebank.reconciliation.web;

import com.corebank.reconciliation.application.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<ReconciliationResponse> reconcile(@PathVariable UUID accountId) {
        return ResponseEntity.ok(ReconciliationResponse.from(reconciliationService.reconcile(accountId)));
    }
}
