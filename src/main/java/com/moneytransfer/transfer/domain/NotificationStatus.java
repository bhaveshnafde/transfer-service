package com.moneytransfer.transfer.domain;

public enum NotificationStatus {
    /** Not sent yet (transfer not completed, or the async send is still running). */
    PENDING,
    SENT,
    /** The notification service failed after retries. The transfer itself is still COMPLETED. */
    FAILED
}
