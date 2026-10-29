package com.moneytransfer.transfer.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Challenge 2: the fingerprint decides whether a repeated key carries the same request. */
class RequestFingerprintTest {

    @Test
    void sameRequestGivesSameFingerprint() {
        assertEquals(
                RequestFingerprint.of("ACC-1", "ACC-2", new BigDecimal("100"), "INR"),
                RequestFingerprint.of("ACC-1", "ACC-2", new BigDecimal("100"), "INR"));
    }

    @Test
    void equivalentAmountsAndCurrencyCaseGiveSameFingerprint() {
        assertEquals(
                RequestFingerprint.of("ACC-1", "ACC-2", new BigDecimal("100"), "INR"),
                RequestFingerprint.of("ACC-1", "ACC-2", new BigDecimal("100.00"), "inr"));
    }

    @Test
    void differentAmountGivesDifferentFingerprint() {
        assertNotEquals(
                RequestFingerprint.of("ACC-1", "ACC-2", new BigDecimal("100"), "INR"),
                RequestFingerprint.of("ACC-1", "ACC-2", new BigDecimal("1000"), "INR"));
    }

    @Test
    void swappedAccountsGiveDifferentFingerprint() {
        assertNotEquals(
                RequestFingerprint.of("ACC-1", "ACC-2", new BigDecimal("100"), "INR"),
                RequestFingerprint.of("ACC-2", "ACC-1", new BigDecimal("100"), "INR"));
    }
}
