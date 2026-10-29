package com.moneytransfer.transfer.infrastructure.client;

import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.saga.LimitsPort;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Contract with the Limits/Fraud service (Member 4):
 * <pre>
 * POST   /api/v1/limit-reservations                {transferId, accountId, amount, currency}
 *        201 reserved (200 if this transferId was already reserved), 422 LIMIT_EXCEEDED
 * DELETE /api/v1/limit-reservations/{transferId}   204 released, 404 nothing to release
 * </pre>
 */
public class LimitsHttpClient implements LimitsPort {

    public record ReserveRequest(String transferId, String accountId, BigDecimal amount, String currency) { }

    private final DownstreamCaller caller;

    public LimitsHttpClient(DownstreamCaller caller) {
        this.caller = caller;
    }

    @Override
    public void reserve(Transfer t) {
        caller.post("/api/v1/limit-reservations",
                new ReserveRequest(t.getId().toString(), t.getSourceAccountId(), t.getAmount(), t.getCurrency()),
                t.getId() + ":limit-reserve", Set.of());
    }

    @Override
    public void release(Transfer t) {
        caller.delete("/api/v1/limit-reservations/" + t.getId(), t.getId() + ":limit-release", Set.of(404));
    }
}
