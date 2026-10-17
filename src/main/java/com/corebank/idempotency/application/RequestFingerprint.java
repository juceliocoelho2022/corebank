package com.corebank.idempotency.application;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class RequestFingerprint {

    public String deposit(UUID accountId, BigDecimal amount, String description) {
        return sha256(canonical(
                "DEPOSIT",
                "accountId", accountId.toString(),
                "amount", money(amount),
                "description", text(description)));
    }

    public String withdrawal(UUID accountId, BigDecimal amount, String description) {
        return sha256(canonical(
                "WITHDRAWAL",
                "accountId", accountId.toString(),
                "amount", money(amount),
                "description", text(description)));
    }

    public String transfer(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount, String description) {
        return sha256(canonical(
                "TRANSFER",
                "sourceAccountId", sourceAccountId.toString(),
                "destinationAccountId", destinationAccountId.toString(),
                "amount", money(amount),
                "description", text(description)));
    }

    private static String money(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount must not be null");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String text(String value) {
        if (value == null) {
            return "<null>";
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return "<null>";
        }
        return normalized.length() + ":" + normalized;
    }

    private static String canonical(String operation, String... fields) {
        StringBuilder builder = new StringBuilder(operation);
        for (int i = 0; i < fields.length; i += 2) {
            builder.append('\n').append(fields[i]).append('=').append(fields[i + 1]);
        }
        return builder.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
