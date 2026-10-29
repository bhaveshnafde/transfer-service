package com.moneytransfer.transfer.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.TransferStatus;
import com.moneytransfer.transfer.web.dto.TransferResponse;
import com.moneytransfer.transfer.web.error.ApiError;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Turns a transfer's state into the HTTP answer for POST /transfers:
 * <ul>
 *   <li>COMPLETED: 201 with the transfer</li>
 *   <li>still in progress (capture outcome unknown): 202 with the transfer; poll GET /transfers/{id}</li>
 *   <li>REJECTED or FAILED: the failure's status (e.g. 409 INSUFFICIENT_FUNDS, 503 LEDGER_UNAVAILABLE)
 *       in the shared error format, with the transferId in details</li>
 * </ul>
 */
@Component
public class ResponseRenderer {

    private final ObjectMapper objectMapper;

    public ResponseRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public StoredResponse render(Transfer t) {
        TransferStatus status = t.getStatus();
        if (status == TransferStatus.COMPLETED) {
            return new StoredResponse(201, json(TransferResponse.from(t)), t.getId());
        }
        if (!status.isTerminal()) {
            return new StoredResponse(202, json(TransferResponse.from(t)), t.getId());
        }
        ApiError error = ApiError.of(t.getFailureCode(), t.getFailureMessage(), List.of(Map.of(
                "transferId", t.getId().toString(),
                "status", status.name())));
        int httpStatus = t.getFailureHttpStatus() == null ? 500 : t.getFailureHttpStatus();
        return new StoredResponse(httpStatus, json(error), t.getId());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise response", e);
        }
    }
}
