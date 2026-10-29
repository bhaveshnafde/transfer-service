package com.moneytransfer.transfer.domain.saga;

import com.moneytransfer.transfer.domain.SagaStep;
import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.TransferStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory stand-ins for every port, so the saga can be tested without HTTP or a database.
 * Each call is recorded by name; failures can be scripted per call name.
 */
class FakeDownstream implements LimitsPort, AccountPort, LedgerPort, TransferCompletedListener, TransferStore, StepLog {

    record LoggedStep(SagaStep step, Action action, Outcome outcome) { }

    final List<String> calls = new ArrayList<>();
    final List<TransferStatus> savedStatuses = new ArrayList<>();
    final List<LoggedStep> stepLog = new ArrayList<>();
    final List<UUID> completedNotifications = new ArrayList<>();
    private final Map<String, Deque<RuntimeException>> scripted = new HashMap<>();
    private RuntimeException listenerFailure;

    /** The next {@code times} calls named {@code call} throw {@code failure}. */
    FakeDownstream failNext(String call, int times, RuntimeException failure) {
        Deque<RuntimeException> queue = scripted.computeIfAbsent(call, k -> new ArrayDeque<>());
        for (int i = 0; i < times; i++) {
            queue.add(failure);
        }
        return this;
    }

    FakeDownstream failAlways(String call, RuntimeException failure) {
        return failNext(call, 1000, failure);
    }

    void clearFailures() {
        scripted.clear();
    }

    void failListenerWith(RuntimeException e) {
        listenerFailure = e;
    }

    long count(String call) {
        return calls.stream().filter(call::equals).count();
    }

    static DownstreamUnavailableException unavailable(String service) {
        return new DownstreamUnavailableException(service, "connection refused", null);
    }

    static DownstreamRejectedException rejected(String service, int status, String code) {
        return new DownstreamRejectedException(service, status, code, code.toLowerCase().replace('_', ' '));
    }

    private void invoke(String call) {
        calls.add(call);
        Deque<RuntimeException> queue = scripted.get(call);
        if (queue != null && !queue.isEmpty()) {
            throw queue.poll();
        }
    }

    @Override public void reserve(Transfer t) { invoke("limits.reserve"); }
    @Override public void release(Transfer t) { invoke("limits.release"); }
    @Override public void placeHold(Transfer t) { invoke("account.hold"); }
    @Override public void captureHold(Transfer t) { invoke("account.capture"); }
    @Override public void releaseHold(Transfer t) { invoke("account.release"); }
    @Override public void postJournal(Transfer t) { invoke("ledger.post"); }
    @Override public void reverseJournal(Transfer t) { invoke("ledger.reverse"); }

    @Override
    public void onCompleted(Transfer t) {
        if (listenerFailure != null) {
            throw listenerFailure;
        }
        completedNotifications.add(t.getId());
    }

    @Override
    public Transfer save(Transfer t) {
        savedStatuses.add(t.getStatus());
        return t;
    }

    @Override
    public void record(UUID transferId, SagaStep step, Action action, Outcome outcome, String detail) {
        stepLog.add(new LoggedStep(step, action, outcome));
    }
}
