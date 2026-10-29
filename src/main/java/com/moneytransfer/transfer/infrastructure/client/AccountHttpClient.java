package com.moneytransfer.transfer.infrastructure.client;

import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.saga.AccountPort;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Contract with the Account service (Member 1). Holds are keyed by transferId:
 * <pre>
 * POST /api/v1/holds                        {transferId, sourceAccountId, destinationAccountId, amount, currency}
 *      201 held (200 if this transferId already has a hold)
 *      409 INSUFFICIENT_FUNDS, 422 ACCOUNT_NOT_ACTIVE / CURRENCY_MISMATCH, 404 ACCOUNT_NOT_FOUND
 * POST /api/v1/holds/{transferId}/capture   200 debited source + credited destination (200 again if already captured)
 *      409 HOLD_NOT_ACTIVE (released or expired)
 * POST /api/v1/holds/{transferId}/release   200 released (200 again if already released), 404 no hold
 *      409 HOLD_ALREADY_CAPTURED
 * </pre>
 */
public class AccountHttpClient implements AccountPort {

    public record HoldRequest(String transferId, String sourceAccountId, String destinationAccountId,
                       BigDecimal amount, String currency) { }

    private final DownstreamCaller caller;

    public AccountHttpClient(DownstreamCaller caller) {
        this.caller = caller;
    }

    @Override
    public void placeHold(Transfer t) {
        caller.post("/api/v1/holds",
                new HoldRequest(t.getId().toString(), t.getSourceAccountId(), t.getDestinationAccountId(),
                        t.getAmount(), t.getCurrency()),
                t.getId() + ":hold", Set.of());
    }

    @Override
    public void captureHold(Transfer t) {
        caller.post("/api/v1/holds/" + t.getId() + "/capture", null, t.getId() + ":capture", Set.of());
    }

    @Override
    public void releaseHold(Transfer t) {
        caller.post("/api/v1/holds/" + t.getId() + "/release", null, t.getId() + ":release", Set.of(404));
    }
}
