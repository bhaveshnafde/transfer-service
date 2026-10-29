package com.moneytransfer.transfer.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moneytransfer.transfer.domain.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId,
        String status,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        String currency,
        String notificationStatus,
        @JsonInclude(JsonInclude.Include.NON_NULL) Failure failure,
        Instant createdAt,
        Instant updatedAt) {

    public record Failure(String code, String message) { }

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.getId(),
                t.getStatus().name(),
                t.getSourceAccountId(),
                t.getDestinationAccountId(),
                t.getAmount(),
                t.getCurrency(),
                t.getNotificationStatus().name(),
                t.getFailureCode() == null ? null : new Failure(t.getFailureCode(), t.getFailureMessage()),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
