package com.moneytransfer.transfer.application;

import com.moneytransfer.transfer.domain.NotificationStatus;
import com.moneytransfer.transfer.domain.SagaStep;
import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.saga.DownstreamRejectedException;
import com.moneytransfer.transfer.domain.saga.DownstreamUnavailableException;
import com.moneytransfer.transfer.domain.saga.RetryPolicy;
import com.moneytransfer.transfer.domain.saga.StepLog;
import com.moneytransfer.transfer.domain.saga.TransferCompletedListener;
import com.moneytransfer.transfer.infrastructure.persistence.TransferJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Step 5, sent after the response so a slow or broken Notification service never delays or
 * fails a transfer. The outcome is recorded on the transfer (notificationStatus) and in the step log.
 */
@Component
public class NotificationDispatcher implements TransferCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationPort notifications;
    private final RetryPolicy retry;
    private final TransferJpaRepository transfers;
    private final StepLog stepLog;
    private final TaskExecutor executor;

    public NotificationDispatcher(NotificationPort notifications, RetryPolicy retry, TransferJpaRepository transfers,
                                  StepLog stepLog, @Qualifier("notificationExecutor") TaskExecutor executor) {
        this.notifications = notifications;
        this.retry = retry;
        this.transfers = transfers;
        this.stepLog = stepLog;
        this.executor = executor;
    }

    @Override
    public void onCompleted(Transfer transfer) {
        executor.execute(() -> send(transfer));
    }

    void send(Transfer t) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("transferId", t.getId().toString())) {
            try {
                retry.run("SEND NOTIFICATION", () -> notifications.sendTransferCompleted(t));
                transfers.updateNotificationStatus(t.getId(), NotificationStatus.SENT);
                stepLog.record(t.getId(), SagaStep.NOTIFICATION, StepLog.Action.EXECUTE,
                        StepLog.Outcome.SUCCEEDED, null);
            } catch (DownstreamUnavailableException e) {
                transfers.updateNotificationStatus(t.getId(), NotificationStatus.FAILED);
                stepLog.record(t.getId(), SagaStep.NOTIFICATION, StepLog.Action.EXECUTE,
                        StepLog.Outcome.UNAVAILABLE, e.getMessage());
                log.warn("Notification not sent; transfer stays COMPLETED: {}", e.getMessage());
            } catch (DownstreamRejectedException e) {
                transfers.updateNotificationStatus(t.getId(), NotificationStatus.FAILED);
                stepLog.record(t.getId(), SagaStep.NOTIFICATION, StepLog.Action.EXECUTE,
                        StepLog.Outcome.REJECTED, e.code() + ": " + e.reason());
                log.warn("Notification rejected; transfer stays COMPLETED: {}", e.getMessage());
            }
        }
    }
}
