package com.corebank.customer.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCustomerRequest(
        @NotBlank @Size(min = 3, max = 120) String name,
        @NotBlank String document,
        @NotBlank @Email String email
) {
}
