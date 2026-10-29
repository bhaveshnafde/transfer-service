package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.FailureKind;
import com.moneytransfer.transfer.domain.SagaStep;
import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Objects;

/**
 * Orchestrated saga for one transfer (Challenge 3C).
 *
 * <pre>
 *  1 LIMIT_RESERVATION  Limits   reserve      undo: release
 *  2 FUNDS_HOLD         Account  place hold   undo: release hold
 *  3 LEDGER_JOURNAL     Ledger   post journal undo: reverse journal
 *  4 CAPTURE            Account  capture hold (commit point, no undo)
 *  5 NOTIFICATION       async, best effort, never fails the transfer
 * </pre>
 *
 * Rules this class follows:
 * <ul>
 *   <li>The transfer is saved after every step, so progress survives a crash. No database
 *       transaction is held open across an HTTP call.</li>
 *   <li>A step that failed or timed out may still have been applied downstream, so
 *       compensation always includes the failed step itself. Undo calls are idempotent, so
 *       undoing something that never happened is harmless.</li>
 *   <li>If an undo fails, the transfer stays COMPENSATING and {@link #resume} finishes it later.</li>
 *   <li>If CAPTURE times out the outcome is unknown, so the transfer stays LEDGER_POSTED and
 *       {@link #resume} retries the (idempotent) capture rather than undoing.</li>
 * </ul>
 */
public final class TransferSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TransferSagaOrchestrator.class);

    private static final List<SagaStep> COMPENSABLE_STEPS =
            List.of(SagaStep.LIMIT_RESERVATION, SagaStep.FUNDS_HOLD, SagaStep.LEDGER_JOURNAL);

    private final LimitsPort limits;
    private final AccountPort accounts;
    private final LedgerPort ledger;
    private final TransferCompletedListener completedListener;
    private final TransferStore store;
    private final StepLog stepLog;
    private final RetryPolicy retry;

    public TransferSagaOrchestrator(LimitsPort limits, AccountPort accounts, LedgerPort ledger,
                                    TransferCompletedListener completedListener, TransferStore store,
                                    StepLog stepLog, RetryPolicy retry) {
        this.limits = Objects.requireNonNull(limits);
        this.accounts = Objects.requireNonNull(accounts);
        this.ledger = Objects.requireNonNull(ledger);
        this.completedListener = Objects.requireNonNull(completedListener);
        this.store = Objects.requireNonNull(store);
        this.stepLog = Objects.requireNonNull(stepLog);
        this.retry = Objects.requireNonNull(retry);
    }

    /** Runs a new transfer from PENDING to a terminal state (or LEDGER_POSTED if capture is unresolved). */
    public Transfer execute(Transfer transfer) {
        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new IllegalStateException("execute() needs a PENDING transfer, got " + transfer.getStatus());
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable("transferId", transfer.getId().toString())) {
            log.info("Saga started: {} {} from {} to {}", transfer.getAmount(), transfer.getCurrency(),
                    transfer.getSourceAccountId(), transfer.getDestinationAccountId());
            Transfer t = transfer;
            for (SagaStep step : COMPENSABLE_STEPS) {
                StepResult result = attempt(t, step, StepLog.Action.EXECUTE);
                if (result.succeeded()) {
                    t.markStepSucceeded(step);
                    t = store.save(t);
                    continue;
                }
                if (step == SagaStep.LIMIT_RESERVATION && result.rejected()) {
                    // Refused at the first step: nothing was applied, nothing to undo.
                    t.reject(result.code(), result.message(), result.httpStatus());
                    t = store.save(t);
                    log.info("Saga ended: REJECTED at {} ({})", step, result.code());
                    return t;
                }
                return compensate(t, step, result);
            }
            return capture(t);
        }
    }

    /**
     * Finishes a transfer left in a non-terminal state, for example after a crash or a failed undo.
     * Used by the recovery job.
     */
    public Transfer resume(Transfer transfer) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("transferId", transfer.getId().toString())) {
            log.info("Resuming transfer stuck in {}", transfer.getStatus());
            return switch (transfer.getStatus()) {
                case LEDGER_POSTED -> capture(transfer);
                case COMPENSATING -> runCompensation(transfer);
                case PENDING, LIMIT_RESERVED, FUNDS_HELD -> compensate(transfer, stepInFlight(transfer.getStatus()),
                        StepResult.unavailable("TRANSFER_INTERRUPTED",
                                "Transfer was interrupted in " + transfer.getStatus() + " and has been rolled back"));
                case COMPLETED, REJECTED, FAILED -> transfer;
            };
        }
    }

    private Transfer capture(Transfer t) {
        StepResult result = attempt(t, SagaStep.CAPTURE, StepLog.Action.EXECUTE);
        if (result.succeeded()) {
            t.markStepSucceeded(SagaStep.CAPTURE);
            t = store.save(t);
            log.info("Saga ended: COMPLETED");
            notifyCompleted(t);
            return t;
        }
        if (result.rejected()) {
            // Account definitely refused the capture (e.g. hold expired): undo journal, hold and limit.
            return compensate(t, SagaStep.LEDGER_JOURNAL, result);
        }
        log.warn("Capture outcome unknown ({}); transfer stays LEDGER_POSTED and the recovery job will retry it",
                result.code());
        return t;
    }

    private Transfer compensate(Transfer t, SagaStep failedStep, StepResult cause) {
        FailureKind kind = cause.rejected() ? FailureKind.BUSINESS : FailureKind.TECHNICAL;
        t.beginCompensation(failedStep, kind, cause.code(), cause.message(), cause.httpStatus());
        t = store.save(t);
        log.warn("Step {} failed ({}); compensating", failedStep, cause.code());
        return runCompensation(t);
    }

    private Transfer runCompensation(Transfer t) {
        for (SagaStep step : COMPENSABLE_STEPS.reversed()) {
            if (step.ordinal() > t.getCompensateFrom().ordinal()) {
                continue;
            }
            StepResult result = attempt(t, step, StepLog.Action.COMPENSATE);
            if (!result.succeeded()) {
                log.error("Undo of {} failed ({}); transfer stays COMPENSATING until the recovery job retries it",
                        step, result.code());
                return t;
            }
        }
        t.finishCompensation();
        t = store.save(t);
        log.info("Saga ended: {} after compensation ({})", t.getStatus(), t.getFailureCode());
        return t;
    }

    private StepResult attempt(Transfer t, SagaStep step, StepLog.Action action) {
        String operation = action + " " + step;
        try {
            retry.run(operation, () -> call(t, step, action));
            stepLog.record(t.getId(), step, action, StepLog.Outcome.SUCCEEDED, null);
            return StepResult.SUCCEEDED;
        } catch (DownstreamRejectedException e) {
            stepLog.record(t.getId(), step, action, StepLog.Outcome.REJECTED, e.code() + ": " + e.reason());
            return StepResult.rejected(e.code(), e.reason(), e.httpStatus());
        } catch (DownstreamUnavailableException e) {
            stepLog.record(t.getId(), step, action, StepLog.Outcome.UNAVAILABLE, e.getMessage());
            return StepResult.unavailable(e.service().toUpperCase().replace('-', '_') + "_UNAVAILABLE",
                    e.getMessage());
        }
    }

    private void call(Transfer t, SagaStep step, StepLog.Action action) {
        boolean undo = action == StepLog.Action.COMPENSATE;
        switch (step) {
            case LIMIT_RESERVATION -> { if (undo) limits.release(t); else limits.reserve(t); }
            case FUNDS_HOLD -> { if (undo) accounts.releaseHold(t); else accounts.placeHold(t); }
            case LEDGER_JOURNAL -> { if (undo) ledger.reverseJournal(t); else ledger.postJournal(t); }
            case CAPTURE -> {
                if (undo) throw new IllegalStateException("CAPTURE has no compensation");
                accounts.captureHold(t);
            }
            case NOTIFICATION -> throw new IllegalStateException("NOTIFICATION is not a saga call");
        }
    }

    private void notifyCompleted(Transfer t) {
        try {
            completedListener.onCompleted(t);
        } catch (RuntimeException e) {
            // A notification problem must never undo or fail a completed transfer.
            log.warn("Could not schedule the completion notification: {}", e.getMessage());
        }
    }

    /** The step that may have been in flight when a transfer stopped in this status. */
    private static SagaStep stepInFlight(TransferStatus status) {
        return switch (status) {
            case PENDING -> SagaStep.LIMIT_RESERVATION;
            case LIMIT_RESERVED -> SagaStep.FUNDS_HOLD;
            case FUNDS_HELD -> SagaStep.LEDGER_JOURNAL;
            default -> throw new IllegalArgumentException("No in-flight compensable step for " + status);
        };
    }

    private record StepResult(StepLog.Outcome outcome, String code, String message, int httpStatus) {

        static final StepResult SUCCEEDED = new StepResult(StepLog.Outcome.SUCCEEDED, null, null, 0);

        static StepResult rejected(String code, String message, int httpStatus) {
            return new StepResult(StepLog.Outcome.REJECTED, code, message, httpStatus);
        }

        static StepResult unavailable(String code, String message) {
            return new StepResult(StepLog.Outcome.UNAVAILABLE, code, message, 503);
        }

        boolean succeeded() { return outcome == StepLog.Outcome.SUCCEEDED; }

        boolean rejected() { return outcome == StepLog.Outcome.REJECTED; }
    }
}
