package com.moneytransfer.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A money transfer and its saga progress. All status changes go through
 * {@link #transitionTo}, which enforces the state machine (BR6).
 *
 * Only IDs of accounts are stored: accounts belong to the Account service.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 64)
    private String sourceAccountId;

    @Column(nullable = false, length = 64)
    private String destinationAccountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    /** Highest step that may have been applied when compensation began; undo runs from here down. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SagaStep compensateFrom;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FailureKind failureKind;

    @Column(length = 60)
    private String failureCode;

    @Column(length = 500)
    private String failureMessage;

    private Integer failureHttpStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus notificationStatus;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: a stale copy (e.g. the recovery job racing a live request) cannot overwrite a newer state. */
    @Version
    private Long version;

    protected Transfer() {
        // for JPA
    }

    public static Transfer create(UUID id, String idempotencyKey, String sourceAccountId,
                                  String destinationAccountId, BigDecimal amount, String currency) {
        Transfer t = new Transfer();
        t.id = Objects.requireNonNull(id);
        t.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        t.sourceAccountId = Objects.requireNonNull(sourceAccountId);
        t.destinationAccountId = Objects.requireNonNull(destinationAccountId);
        t.amount = Objects.requireNonNull(amount);
        t.currency = Objects.requireNonNull(currency);
        t.status = TransferStatus.PENDING;
        t.notificationStatus = NotificationStatus.PENDING;
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        return t;
    }

    /** A saga step succeeded: move to the status that step leads to. */
    public void markStepSucceeded(SagaStep step) {
        if (step.statusOnSuccess() == null) {
            throw new IllegalArgumentException(step + " does not change the transfer status");
        }
        transitionTo(step.statusOnSuccess());
    }

    /** Step 1 refused the transfer and nothing was applied, so no compensation is needed. */
    public void reject(String code, String message, int httpStatus) {
        recordFailure(FailureKind.BUSINESS, code, message, httpStatus);
        transitionTo(TransferStatus.REJECTED);
    }

    public void beginCompensation(SagaStep from, FailureKind kind, String code, String message, int httpStatus) {
        if (!from.isCompensable()) {
            throw new IllegalArgumentException(from + " cannot be compensated");
        }
        recordFailure(kind, code, message, httpStatus);
        this.compensateFrom = from;
        transitionTo(TransferStatus.COMPENSATING);
    }

    public void finishCompensation() {
        transitionTo(failureKind == FailureKind.BUSINESS ? TransferStatus.REJECTED : TransferStatus.FAILED);
    }

    public void markNotification(NotificationStatus notificationStatus) {
        this.notificationStatus = notificationStatus;
    }

    private void recordFailure(FailureKind kind, String code, String message, int httpStatus) {
        this.failureKind = kind;
        this.failureCode = code;
        this.failureMessage = message == null ? null : truncate(message, 500);
        this.failureHttpStatus = httpStatus;
    }

    private void transitionTo(TransferStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateTransitionException(id, status, next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSourceAccountId() { return sourceAccountId; }
    public String getDestinationAccountId() { return destinationAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public TransferStatus getStatus() { return status; }
    public SagaStep getCompensateFrom() { return compensateFrom; }
    public FailureKind getFailureKind() { return failureKind; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Integer getFailureHttpStatus() { return failureHttpStatus; }
    public NotificationStatus getNotificationStatus() { return notificationStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
