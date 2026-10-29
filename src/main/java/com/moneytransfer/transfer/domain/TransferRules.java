package com.moneytransfer.transfer.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Business rules the Transfer service can check on its own, before contacting anyone.
 *
 * BR1: amount must be greater than 0, at most the per-transaction cap, with at most 2 decimals.
 * BR4 (local part): source and destination must be different accounts.
 *      The rest of BR4 (both ACTIVE, same currency) needs account data, so the Account service enforces it.
 */
public final class TransferRules {

    public record Violation(String field, String code, String message) { }

    private final BigDecimal maxAmountPerTransfer;

    public TransferRules(BigDecimal maxAmountPerTransfer) {
        this.maxAmountPerTransfer = Objects.requireNonNull(maxAmountPerTransfer);
    }

    public List<Violation> check(String sourceAccountId, String destinationAccountId, BigDecimal amount) {
        List<Violation> violations = new ArrayList<>();

        if (amount != null) {
            if (amount.signum() <= 0) {
                violations.add(new Violation("amount", "BR1_AMOUNT_NOT_POSITIVE",
                        "amount must be greater than 0"));
            } else if (amount.compareTo(maxAmountPerTransfer) > 0) {
                violations.add(new Violation("amount", "BR1_AMOUNT_ABOVE_CAP",
                        "amount must not exceed " + maxAmountPerTransfer.toPlainString() + " per transfer"));
            }
            if (amount.stripTrailingZeros().scale() > 2) {
                violations.add(new Violation("amount", "BR1_TOO_MANY_DECIMALS",
                        "amount may have at most 2 decimal places"));
            }
        }

        if (sourceAccountId != null && sourceAccountId.equals(destinationAccountId)) {
            violations.add(new Violation("destinationAccountId", "BR4_SAME_ACCOUNT",
                    "source and destination accounts must be different"));
        }

        return violations;
    }
}
