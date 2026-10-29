package com.moneytransfer.transfer.web;

import com.moneytransfer.transfer.application.StoredResponse;
import com.moneytransfer.transfer.application.TransferApplicationService;
import com.moneytransfer.transfer.infrastructure.persistence.SagaStepJpaRepository;
import com.moneytransfer.transfer.infrastructure.persistence.TransferJpaRepository;
import com.moneytransfer.transfer.web.dto.SagaStepResponse;
import com.moneytransfer.transfer.web.dto.TransferRequest;
import com.moneytransfer.transfer.web.dto.TransferResponse;
import com.moneytransfer.transfer.web.error.ApiError;
import com.moneytransfer.transfer.web.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Start a money transfer and follow its saga")
public class TransferController {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final TransferApplicationService service;
    private final TransferJpaRepository transfers;
    private final SagaStepJpaRepository sagaSteps;

    public TransferController(TransferApplicationService service, TransferJpaRepository transfers,
                              SagaStepJpaRepository sagaSteps) {
        this.service = service;
        this.transfers = transfers;
        this.sagaSteps = sagaSteps;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transfer money between two accounts",
            description = "Runs the transfer saga. Repeating the request with the same Idempotency-Key returns "
                    + "the stored result and has no second effect (header Idempotent-Replayed: true).")
    @ApiResponse(responseCode = "201", description = "Transfer COMPLETED",
            content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @ApiResponse(responseCode = "202", description = "Accepted; capture still being confirmed. Poll GET /transfers/{id}",
            content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @ApiResponse(responseCode = "400", description = "Malformed body, invalid fields, missing Idempotency-Key",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_FUNDS, or REQUEST_IN_PROGRESS for a concurrent repeat",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "A business rule refused it (BR1_*, BR4_*, LIMIT_EXCEEDED, "
            + "ACCOUNT_NOT_ACTIVE) or IDEMPOTENCY_KEY_REUSED with a different body",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "A dependency was unavailable; all completed steps were undone",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<String> create(
            @Parameter(in = ParameterIn.HEADER, required = true, example = "7c0e7d7e-demo-0001",
                    description = "Client-chosen unique key per transfer, 1-100 chars [A-Za-z0-9._:-]")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        if (!KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw new ApiException(400, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-100 characters of letters, digits, '.', '_', ':' or '-'");
        }

        TransferApplicationService.Result result = service.submit(idempotencyKey, request);
        StoredResponse response = result.response();

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()));
        if ((response.status() == 201 || response.status() == 202) && response.transferId() != null) {
            builder.location(URI.create("/api/v1/transfers/" + response.transferId()));
        }
        // The stored JSON text as-is, so a replay is byte-for-byte the original response
        return builder.body(response.json());
    }

    @GetMapping(value = "/{transferId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a transfer and its current status")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = TransferResponse.class)))
    @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public TransferResponse get(@PathVariable UUID transferId) {
        return transfers.findById(transferId)
                .map(TransferResponse::from)
                .orElseThrow(() -> notFound(transferId));
    }

    @GetMapping(value = "/{transferId}/saga-steps", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Audit trail of every saga step and compensation for a transfer")
    @ApiResponse(responseCode = "404", description = "TRANSFER_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public List<SagaStepResponse> sagaSteps(@PathVariable UUID transferId) {
        if (!transfers.existsById(transferId)) {
            throw notFound(transferId);
        }
        return sagaSteps.findByTransferIdOrderByIdAsc(transferId).stream().map(SagaStepResponse::from).toList();
    }

    private static ApiException notFound(UUID transferId) {
        return new ApiException(404, "TRANSFER_NOT_FOUND", "No transfer " + transferId);
    }
}
