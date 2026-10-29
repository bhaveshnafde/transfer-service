package com.moneytransfer.transfer.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Transfer lifecycle (BR6). Every status change goes through {@link #canTransitionTo};
 * terminal statuses accept no further transitions.
 *
 * <pre>
 * PENDING -> LIMIT_RESERVED -> FUNDS_HELD -> LEDGER_POSTED -> COMPLETED
 *    |            |               |              |
 *    +------------+-------> COMPENSATING <-------+
 *    |                          |
 *    +--> REJECTED <------------+----> FAILED
 * </pre>
 */
public enum TransferStatus {
    PENDING,
    LIMIT_RESERVED,
    FUNDS_HELD,
    LEDGER_POSTED,
    COMPENSATING,
    COMPLETED,
    REJECTED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == REJECTED || this == FAILED;
    }

    public Set<TransferStatus> allowedNext() {
        return switch (this) {
            // REJECTED straight from PENDING: the limit check refused it and nothing was applied
            case PENDING -> EnumSet.of(LIMIT_RESERVED, REJECTED, COMPENSATING);
            case LIMIT_RESERVED -> EnumSet.of(FUNDS_HELD, COMPENSATING);
            case FUNDS_HELD -> EnumSet.of(LEDGER_POSTED, COMPENSATING);
            case LEDGER_POSTED -> EnumSet.of(COMPLETED, COMPENSATING);
            // REJECTED when a business rule refused a later step, FAILED when a dependency failed
            case COMPENSATING -> EnumSet.of(REJECTED, FAILED);
            case COMPLETED, REJECTED, FAILED -> EnumSet.noneOf(TransferStatus.class);
        };
    }

    public boolean canTransitionTo(TransferStatus next) {
        return allowedNext().contains(next);
    }
}
