package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.Transfer;

/** Ledger service. One journal per transferId (BR5), so both calls are idempotent. */
public interface LedgerPort {

    /** Post a balanced double-entry journal: debit source, credit destination. */
    void postJournal(Transfer transfer);

    /** Post the reversing journal. Must succeed if no journal exists. */
    void reverseJournal(Transfer transfer);
}
