package com.moneytransfer.transfer.application;

import com.moneytransfer.transfer.domain.Transfer;

/** Notification service. Idempotent per transferId. */
public interface NotificationPort {

    void sendTransferCompleted(Transfer transfer);
}
