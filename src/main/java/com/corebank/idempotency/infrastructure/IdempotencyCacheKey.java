package com.corebank.idempotency.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class IdempotencyCacheKey {
    private IdempotencyCacheKey() {}

    public static String sha256Hex(String rawKey) {
        Objects.requireNonNull(rawKey, "rawKey");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static String redisKey(String prefix, String rawKey) {
        Objects.requireNonNull(prefix, "prefix");
        return prefix + "idempotency:" + sha256Hex(rawKey);
    }
}
