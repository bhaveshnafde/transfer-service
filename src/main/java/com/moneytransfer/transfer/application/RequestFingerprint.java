package com.moneytransfer.transfer.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 fingerprint of a transfer request, stored next to its Idempotency-Key.
 * The same key with a different fingerprint is rejected (422).
 *
 * Fields are normalised first, so 100, 100.0 and 100.00 count as the same amount
 * and "inr" equals "INR".
 */
public final class RequestFingerprint {

    private RequestFingerprint() { }

    public static String of(String sourceAccountId, String destinationAccountId, BigDecimal amount, String currency) {
        String canonical = String.join("|",
                "v1",
                sourceAccountId,
                destinationAccountId,
                amount.stripTrailingZeros().toPlainString(),
                currency.toUpperCase());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
