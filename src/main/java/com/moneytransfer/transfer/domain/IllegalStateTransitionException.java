package com.moneytransfer.transfer.domain;

import java.util.UUID;

public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(UUID transferId, TransferStatus from, TransferStatus to) {
        super("Transfer " + transferId + " cannot move from " + from + " to " + to
                + " (allowed: " + from.allowedNext() + ")");
    }
}
