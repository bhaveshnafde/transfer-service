package com.moneytransfer.transfer.infrastructure.client;

import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.saga.LedgerPort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Contract with the Ledger service (Member 3). One journal per transferId (BR5):
 * <pre>
 * POST /api/v1/journals                        {transferId, currency, entries:[{accountId, direction, amount}, ...]}
 *      201 posted (200 if this transferId already has a journal), 422 UNBALANCED_JOURNAL
 * POST /api/v1/journals/{transferId}/reversal  201 reversed (200 if already reversed), 404 no journal
 * </pre>
 */
public class LedgerHttpClient implements LedgerPort {

    public record Entry(String accountId, String direction, BigDecimal amount) { }

    public record JournalRequest(String transferId, String currency, List<Entry> entries) { }

    private final DownstreamCaller caller;

    public LedgerHttpClient(DownstreamCaller caller) {
        this.caller = caller;
    }

    @Override
    public void postJournal(Transfer t) {
        caller.post("/api/v1/journals",
                new JournalRequest(t.getId().toString(), t.getCurrency(), List.of(
                        new Entry(t.getSourceAccountId(), "DEBIT", t.getAmount()),
                        new Entry(t.getDestinationAccountId(), "CREDIT", t.getAmount()))),
                t.getId() + ":journal", Set.of());
    }

    @Override
    public void reverseJournal(Transfer t) {
        caller.post("/api/v1/journals/" + t.getId() + "/reversal", null, t.getId() + ":journal-reversal", Set.of(404));
    }
}
