package com.moneytransfer.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BR1 (amount limits) and the local part of BR4 (different accounts). */
class TransferRulesTest {

    private final TransferRules rules = new TransferRules(new BigDecimal("100000.00"));

    @Test
    void validTransferPasses() {
        assertTrue(rules.check("ACC-1", "ACC-2", new BigDecimal("1500.50")).isEmpty());
    }

    @Test
    void BR1_zeroAmountIsRejected() {
        assertOnlyViolation(rules.check("ACC-1", "ACC-2", BigDecimal.ZERO), "BR1_AMOUNT_NOT_POSITIVE");
    }

    @Test
    void BR1_negativeAmountIsRejected() {
        assertOnlyViolation(rules.check("ACC-1", "ACC-2", new BigDecimal("-5")), "BR1_AMOUNT_NOT_POSITIVE");
    }

    @Test
    void BR1_amountExactlyAtCapIsAllowed() {
        assertTrue(rules.check("ACC-1", "ACC-2", new BigDecimal("100000.00")).isEmpty());
    }

    @Test
    void BR1_amountAboveCapIsRejected() {
        assertOnlyViolation(rules.check("ACC-1", "ACC-2", new BigDecimal("100000.01")), "BR1_AMOUNT_ABOVE_CAP");
    }

    @Test
    void BR1_moreThanTwoDecimalsIsRejected() {
        assertOnlyViolation(rules.check("ACC-1", "ACC-2", new BigDecimal("10.005")), "BR1_TOO_MANY_DECIMALS");
    }

    @Test
    void BR1_trailingZerosDoNotCountAsDecimals() {
        assertTrue(rules.check("ACC-1", "ACC-2", new BigDecimal("10.5000")).isEmpty());
    }

    @Test
    void BR4_sameSourceAndDestinationIsRejected() {
        assertOnlyViolation(rules.check("ACC-1", "ACC-1", new BigDecimal("10")), "BR4_SAME_ACCOUNT");
    }

    private static void assertOnlyViolation(List<TransferRules.Violation> violations, String code) {
        assertEquals(1, violations.size(), "violations: " + violations);
        assertEquals(code, violations.getFirst().code());
    }
}
