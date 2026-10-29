package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.FailureKind;
import com.moneytransfer.transfer.domain.SagaStep;
import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.TransferStatus;
import com.moneytransfer.transfer.domain.saga.FakeDownstream.LoggedStep;
import com.moneytransfer.transfer.domain.saga.StepLog.Action;
import com.moneytransfer.transfer.domain.saga.StepLog.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static com.moneytransfer.transfer.domain.saga.FakeDownstream.rejected;
import static com.moneytransfer.transfer.domain.saga.FakeDownstream.unavailable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The orchestrated saga (Challenge 3C), including the BR2 and BR3 rejection paths. */
class TransferSagaOrchestratorTest {

    private FakeDownstream fake;
    private TransferSagaOrchestrator saga;

    @BeforeEach
    void setUp() {
        fake = new FakeDownstream();
        RetryPolicy noWaitRetry = new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(100),
                d -> { }, new Random(1));
        saga = new TransferSagaOrchestrator(fake, fake, fake, fake, fake, fake, noWaitRetry);
    }

    private static Transfer newTransfer() {
        return Transfer.create(UUID.randomUUID(), "key-" + UUID.randomUUID(), "ACC-1", "ACC-2",
                new BigDecimal("500.00"), "INR");
    }

    @Test
    void happyPath_runsAllStepsInOrderAndCompletes() {
        Transfer t = saga.execute(newTransfer());

        assertEquals(TransferStatus.COMPLETED, t.getStatus());
        assertEquals(List.of("limits.reserve", "account.hold", "ledger.post", "account.capture"), fake.calls);
        assertEquals(List.of(TransferStatus.LIMIT_RESERVED, TransferStatus.FUNDS_HELD,
                TransferStatus.LEDGER_POSTED, TransferStatus.COMPLETED), fake.savedStatuses);
        assertEquals(List.of(t.getId()), fake.completedNotifications);
    }

    @Test
    void BR3_limitExceeded_isRejectedWithNothingToUndo() {
        fake.failNext("limits.reserve", 1, rejected("limits", 422, "LIMIT_EXCEEDED"));

        Transfer t = saga.execute(newTransfer());

        assertEquals(TransferStatus.REJECTED, t.getStatus());
        assertEquals("LIMIT_EXCEEDED", t.getFailureCode());
        assertEquals(422, t.getFailureHttpStatus());
        assertEquals(List.of("limits.reserve"), fake.calls, "a rejection is never retried and nothing is undone");
        assertTrue(fake.completedNotifications.isEmpty());
    }

    @Test
    void BR2_insufficientFunds_releasesTheLimitReservationAndRejects() {
        fake.failNext("account.hold", 1, rejected("account", 409, "INSUFFICIENT_FUNDS"));

        Transfer t = saga.execute(newTransfer());

        assertEquals(TransferStatus.REJECTED, t.getStatus());
        assertEquals(FailureKind.BUSINESS, t.getFailureKind());
        assertEquals("INSUFFICIENT_FUNDS", t.getFailureCode());
        assertEquals(409, t.getFailureHttpStatus());
        assertEquals(List.of("limits.reserve", "account.hold", "account.release", "limits.release"), fake.calls);
    }

    @Test
    void ledgerDownAtStep3_undoesSteps2And1InReverseOrderAndFails() {
        fake.failAlways("ledger.post", unavailable("ledger"));

        Transfer t = saga.execute(newTransfer());

        assertEquals(TransferStatus.FAILED, t.getStatus());
        assertEquals(FailureKind.TECHNICAL, t.getFailureKind());
        assertEquals("LEDGER_UNAVAILABLE", t.getFailureCode());
        assertEquals(503, t.getFailureHttpStatus());
        assertEquals(List.of(
                "limits.reserve", "account.hold",
                "ledger.post", "ledger.post", "ledger.post",          // 3 attempts with backoff
                "ledger.reverse", "account.release", "limits.release"  // undo, newest first
        ), fake.calls);
        assertEquals(List.of(
                new LoggedStep(SagaStep.LIMIT_RESERVATION, Action.EXECUTE, Outcome.SUCCEEDED),
                new LoggedStep(SagaStep.FUNDS_HOLD, Action.EXECUTE, Outcome.SUCCEEDED),
                new LoggedStep(SagaStep.LEDGER_JOURNAL, Action.EXECUTE, Outcome.UNAVAILABLE),
                new LoggedStep(SagaStep.LEDGER_JOURNAL, Action.COMPENSATE, Outcome.SUCCEEDED),
                new LoggedStep(SagaStep.FUNDS_HOLD, Action.COMPENSATE, Outcome.SUCCEEDED),
                new LoggedStep(SagaStep.LIMIT_RESERVATION, Action.COMPENSATE, Outcome.SUCCEEDED)
        ), fake.stepLog);
        assertTrue(fake.completedNotifications.isEmpty());
    }

    @Test
    void transientFailureIsRetriedAndTheTransferStillCompletes() {
        fake.failNext("account.hold", 1, unavailable("account"));

        Transfer t = saga.execute(newTransfer());

        assertEquals(TransferStatus.COMPLETED, t.getStatus());
        assertEquals(2, fake.count("account.hold"));
    }

    @Test
    void captureOutcomeUnknown_staysLedgerPosted_thenResumeCompletesWithoutUndoing() {
        fake.failAlways("account.capture", unavailable("account"));

        Transfer t = saga.execute(newTransfer());
        assertEquals(TransferStatus.LEDGER_POSTED, t.getStatus(), "capture may have happened, so do not undo");
        assertEquals(0, fake.count("ledger.reverse"));

        fake.clearFailures();
        t = saga.resume(t);

        assertEquals(TransferStatus.COMPLETED, t.getStatus());
        assertEquals(0, fake.count("account.release"));
        assertEquals(List.of(t.getId()), fake.completedNotifications);
    }

    @Test
    void captureRejected_undoesJournalHoldAndLimit() {
        fake.failNext("account.capture", 1, rejected("account", 409, "HOLD_NOT_ACTIVE"));

        Transfer t = saga.execute(newTransfer());

        assertEquals(TransferStatus.REJECTED, t.getStatus());
        assertEquals("HOLD_NOT_ACTIVE", t.getFailureCode());
        assertEquals(List.of("ledger.reverse", "account.release", "limits.release"),
                fake.calls.subList(4, fake.calls.size()));
    }

    @Test
    void failedUndo_staysCompensating_andResumeFinishesTheRollback() {
        fake.failAlways("ledger.post", unavailable("ledger"));
        fake.failNext("account.release", 3, unavailable("account"));

        Transfer t = saga.execute(newTransfer());
        assertEquals(TransferStatus.COMPENSATING, t.getStatus());
        assertEquals(0, fake.count("limits.release"), "stops at the first failed undo");

        fake.clearFailures();
        t = saga.resume(t);

        assertEquals(TransferStatus.FAILED, t.getStatus());
        assertEquals(1, fake.count("limits.release"));
        assertEquals(2, fake.count("ledger.reverse"), "undo calls are idempotent, repeating them is safe");
    }

    @Test
    void transferInterruptedMidSaga_isRolledBackByResume() {
        Transfer t = newTransfer();
        t.markStepSucceeded(SagaStep.LIMIT_RESERVATION);
        t.markStepSucceeded(SagaStep.FUNDS_HOLD);
        // the service crashed here, while the ledger call may have been in flight

        t = saga.resume(t);

        assertEquals(TransferStatus.FAILED, t.getStatus());
        assertEquals("TRANSFER_INTERRUPTED", t.getFailureCode());
        assertEquals(List.of("ledger.reverse", "account.release", "limits.release"), fake.calls);
    }

    @Test
    void resumeLeavesFinishedTransfersAlone() {
        Transfer t = saga.execute(newTransfer());
        int callsBefore = fake.calls.size();

        assertEquals(TransferStatus.COMPLETED, saga.resume(t).getStatus());
        assertEquals(callsBefore, fake.calls.size());
    }

    @Test
    void notificationProblemNeverFailsACompletedTransfer() {
        fake.failListenerWith(new IllegalStateException("executor queue full"));

        Transfer t = saga.execute(newTransfer());

        assertEquals(TransferStatus.COMPLETED, t.getStatus());
    }
}
