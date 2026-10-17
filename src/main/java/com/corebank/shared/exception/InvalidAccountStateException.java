package com.corebank.shared.exception;

public class InvalidAccountStateException extends ConflictException {
    public InvalidAccountStateException(String status) {
        super("Account does not allow money movements while status is " + status);
    }
}
