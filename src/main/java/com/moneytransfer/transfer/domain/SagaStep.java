package com.moneytransfer.transfer.domain;

/**
 * The steps of the transfer saga, in execution order. Compensation runs in reverse order.
 * CAPTURE and NOTIFICATION have no compensation: CAPTURE is the commit point, and
 * NOTIFICATION is best-effort and never fails a transfer.
 */
public enum SagaStep {
    LIMIT_RESERVATION(TransferStatus.LIMIT_RESERVED, true),
    FUNDS_HOLD(TransferStatus.FUNDS_HELD, true),
    LEDGER_JOURNAL(TransferStatus.LEDGER_POSTED, true),
    CAPTURE(TransferStatus.COMPLETED, false),
    NOTIFICATION(null, false);

    private final TransferStatus statusOnSuccess;
    private final boolean compensable;

    SagaStep(TransferStatus statusOnSuccess, boolean compensable) {
        this.statusOnSuccess = statusOnSuccess;
        this.compensable = compensable;
    }

    public TransferStatus statusOnSuccess() {
        return statusOnSuccess;
    }

    public boolean isCompensable() {
        return compensable;
    }
}
