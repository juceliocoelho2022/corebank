package com.corebank.idempotency.application;

import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyValidator {

    public String validate(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        if (key.length() < 8 || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must contain between 8 and 128 characters");
        }
        return key;
    }
}
