package com.moneytransfer.transfer.web.dto;

import com.moneytransfer.transfer.infrastructure.persistence.SagaStepRecord;

import java.time.Instant;

public record SagaStepResponse(long seq, String step, String action, String outcome, String detail, Instant at) {

    public static SagaStepResponse from(SagaStepRecord r) {
        return new SagaStepResponse(r.getId(), r.getStep().name(), r.getAction().name(), r.getOutcome().name(),
                r.getDetail(), r.getCreatedAt());
    }
}
