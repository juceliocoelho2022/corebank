package com.corebank.transaction.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record MoneyMovementRequest(
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @Size(max = 200) String description) {
    public MoneyMovementRequest {
        if (description != null) {
            description = description.trim();
            if (description.isEmpty()) description = null;
        }
    }
}
