package com.corebank.customer.web;

import com.corebank.customer.application.CustomerService;
import com.corebank.customer.application.CustomerSummaryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService service;
    private final CustomerSummaryService summaryService;

    public CustomerController(CustomerService service, CustomerSummaryService summaryService) {
        this.service = service;
        this.summaryService = summaryService;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse response = CustomerResponse.from(
                service.create(request.name(), request.document(), request.email()));
        return ResponseEntity.created(URI.create("/api/v1/customers/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.from(service.get(id));
    }

    @GetMapping("/{id}/summary")
    public CustomerSummaryResponse summary(@PathVariable UUID id) {
        return CustomerSummaryResponse.from(summaryService.get(id));
    }
}

