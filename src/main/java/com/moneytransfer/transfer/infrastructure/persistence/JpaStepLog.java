package com.moneytransfer.transfer.infrastructure.persistence;

import com.moneytransfer.transfer.domain.SagaStep;
import com.moneytransfer.transfer.domain.saga.StepLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class JpaStepLog implements StepLog {

    private static final Logger log = LoggerFactory.getLogger(JpaStepLog.class);

    private final SagaStepJpaRepository repository;

    public JpaStepLog(SagaStepJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(UUID transferId, SagaStep step, Action action, Outcome outcome, String detail) {
        log.info("Saga step {} {} -> {}{}", action, step, outcome, detail == null ? "" : " (" + detail + ")");
        repository.save(new SagaStepRecord(transferId, step, action, outcome, detail));
    }
}
