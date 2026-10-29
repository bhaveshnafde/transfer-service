package com.moneytransfer.transfer.infrastructure.persistence;

import com.moneytransfer.transfer.domain.SagaStep;
import com.moneytransfer.transfer.domain.saga.StepLog;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saga_steps")
public class SagaStepRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SagaStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StepLog.Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StepLog.Outcome outcome;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    protected SagaStepRecord() {
        // for JPA
    }

    public SagaStepRecord(UUID transferId, SagaStep step, StepLog.Action action, StepLog.Outcome outcome, String detail) {
        this.transferId = transferId;
        this.step = step;
        this.action = action;
        this.outcome = outcome;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getTransferId() { return transferId; }
    public SagaStep getStep() { return step; }
    public StepLog.Action getAction() { return action; }
    public StepLog.Outcome getOutcome() { return outcome; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
