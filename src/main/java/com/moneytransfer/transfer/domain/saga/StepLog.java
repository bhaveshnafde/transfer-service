package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.SagaStep;

import java.util.UUID;

/** Audit trail of every saga step and compensation, used as evidence for the saga challenge. */
public interface StepLog {

    enum Action { EXECUTE, COMPENSATE }

    enum Outcome { SUCCEEDED, REJECTED, UNAVAILABLE }

    void record(UUID transferId, SagaStep step, Action action, Outcome outcome, String detail);
}
