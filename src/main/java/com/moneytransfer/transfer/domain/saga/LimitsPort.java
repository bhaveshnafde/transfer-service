package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.Transfer;

/** Limits/Fraud service. Both calls are idempotent per transferId. */
public interface LimitsPort {

    /** Reserve today's limit usage for this transfer (BR3). Rejects with LIMIT_EXCEEDED. */
    void reserve(Transfer transfer);

    /** Undo the reservation. Must succeed if there is nothing to release. */
    void release(Transfer transfer);
}
