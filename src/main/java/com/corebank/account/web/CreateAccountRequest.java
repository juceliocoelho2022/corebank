package com.corebank.account.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateAccountRequest(@NotNull UUID customerId) {
}
