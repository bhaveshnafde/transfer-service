package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.Transfer;

/** Account service. Holds are keyed by transferId, so every call is idempotent. */
public interface AccountPort {

    /** Reserve the amount on the source account (BR2, BR4). Rejects with INSUFFICIENT_FUNDS, ACCOUNT_NOT_ACTIVE, ... */
    void placeHold(Transfer transfer);

    /** Commit point: debit source and credit destination in one local transaction. */
    void captureHold(Transfer transfer);

    /** Undo the hold. Must succeed if there is nothing to release. */
    void releaseHold(Transfer transfer);
}
