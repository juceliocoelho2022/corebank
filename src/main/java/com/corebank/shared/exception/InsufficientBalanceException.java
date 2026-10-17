package com.corebank.shared.exception;

public class InsufficientBalanceException extends ConflictException {
    public InsufficientBalanceException() {
        super("Insufficient balance");
    }
}
