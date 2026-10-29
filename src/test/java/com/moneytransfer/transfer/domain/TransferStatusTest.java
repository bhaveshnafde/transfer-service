package com.moneytransfer.transfer.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BR6: only allowed state transitions; terminal states are final. */
class TransferStatusTest {

    @Test
    void BR6_happyPathTransitionsAreAllowed() {
        assertTrue(TransferStatus.PENDING.canTransitionTo(TransferStatus.LIMIT_RESERVED));
        assertTrue(TransferStatus.LIMIT_RESERVED.canTransitionTo(TransferStatus.FUNDS_HELD));
        assertTrue(TransferStatus.FUNDS_HELD.canTransitionTo(TransferStatus.LEDGER_POSTED));
        assertTrue(TransferStatus.LEDGER_POSTED.canTransitionTo(TransferStatus.COMPLETED));
    }

    @Test
    void BR6_skippingAStepIsNotAllowed() {
        assertFalse(TransferStatus.PENDING.canTransitionTo(TransferStatus.COMPLETED));
        assertFalse(TransferStatus.LIMIT_RESERVED.canTransitionTo(TransferStatus.LEDGER_POSTED));
        assertFalse(TransferStatus.FUNDS_HELD.canTransitionTo(TransferStatus.COMPLETED));
    }

    @Test
    void BR6_terminalStatusesAcceptNoTransition() {
        for (TransferStatus terminal : new TransferStatus[]{
                TransferStatus.COMPLETED, TransferStatus.REJECTED, TransferStatus.FAILED}) {
            assertTrue(terminal.isTerminal());
            for (TransferStatus next : TransferStatus.values()) {
                assertFalse(terminal.canTransitionTo(next), terminal + " -> " + next + " must be refused");
            }
        }
    }

    @Test
    void BR6_compensationEndsOnlyInRejectedOrFailed() {
        assertTrue(TransferStatus.COMPENSATING.canTransitionTo(TransferStatus.REJECTED));
        assertTrue(TransferStatus.COMPENSATING.canTransitionTo(TransferStatus.FAILED));
        assertFalse(TransferStatus.COMPENSATING.canTransitionTo(TransferStatus.COMPLETED));
    }

    @Test
    void BR6_transferEntityRefusesAnIllegalTransition() {
        Transfer t = Transfer.create(UUID.randomUUID(), "key-1", "ACC-1", "ACC-2", new BigDecimal("10.00"), "INR");
        // PENDING cannot jump straight to COMPLETED
        assertThrows(IllegalStateTransitionException.class, () -> t.markStepSucceeded(SagaStep.CAPTURE));
        assertEquals(TransferStatus.PENDING, t.getStatus());
    }

    @Test
    void BR6_completedTransferCannotBeCompensated() {
        Transfer t = Transfer.create(UUID.randomUUID(), "key-2", "ACC-1", "ACC-2", new BigDecimal("10.00"), "INR");
        t.markStepSucceeded(SagaStep.LIMIT_RESERVATION);
        t.markStepSucceeded(SagaStep.FUNDS_HOLD);
        t.markStepSucceeded(SagaStep.LEDGER_JOURNAL);
        t.markStepSucceeded(SagaStep.CAPTURE);
        assertEquals(TransferStatus.COMPLETED, t.getStatus());
        assertThrows(IllegalStateTransitionException.class, () ->
                t.beginCompensation(SagaStep.LEDGER_JOURNAL, FailureKind.TECHNICAL, "X", "late failure", 503));
    }
}
