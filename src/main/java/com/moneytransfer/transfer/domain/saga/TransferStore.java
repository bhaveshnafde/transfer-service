package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.Transfer;

/** Persists the transfer after every step, so a crash never loses saga progress. */
public interface TransferStore {

    /** Saves and returns the saved copy, which must be used from then on (it carries the new version). */
    Transfer save(Transfer transfer);
}
