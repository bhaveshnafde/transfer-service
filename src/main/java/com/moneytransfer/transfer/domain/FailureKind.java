package com.moneytransfer.transfer.domain;

public enum FailureKind {
    /** A business rule refused the transfer (limit exceeded, insufficient funds). Ends REJECTED. */
    BUSINESS,
    /** A dependency was down or timed out. Ends FAILED. */
    TECHNICAL
}
