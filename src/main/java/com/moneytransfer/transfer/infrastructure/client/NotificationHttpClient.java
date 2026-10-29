package com.moneytransfer.transfer.infrastructure.client;

import com.moneytransfer.transfer.application.NotificationPort;
import com.moneytransfer.transfer.domain.Transfer;

import java.util.Set;

/**
 * Contract with the Notification mock (Member 4). It can be slow or fail on purpose
 * (NOTIFY_DELAY_MS, NOTIFY_FAIL_RATE on that service):
 * <pre>
 * POST /api/v1/notifications  {transferId, accountId, channel, template, message}
 *      202 accepted (also 202 again for the same transferId)
 * </pre>
 */
public class NotificationHttpClient implements NotificationPort {

    public record NotificationRequest(String transferId, String accountId, String channel, String template, String message) { }

    private final DownstreamCaller caller;

    public NotificationHttpClient(DownstreamCaller caller) {
        this.caller = caller;
    }

    @Override
    public void sendTransferCompleted(Transfer t) {
        String message = "You sent " + t.getAmount().toPlainString() + " " + t.getCurrency()
                + " to " + t.getDestinationAccountId() + ". Ref " + t.getId();
        caller.post("/api/v1/notifications",
                new NotificationRequest(t.getId().toString(), t.getSourceAccountId(), "SMS",
                        "TRANSFER_COMPLETED", message),
                t.getId() + ":notify", Set.of());
    }
}
