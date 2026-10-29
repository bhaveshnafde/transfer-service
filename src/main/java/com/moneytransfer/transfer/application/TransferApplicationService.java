package com.moneytransfer.transfer.application;

import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.domain.TransferRules;
import com.moneytransfer.transfer.domain.saga.TransferSagaOrchestrator;
import com.moneytransfer.transfer.infrastructure.persistence.IdempotencyRepository;
import com.moneytransfer.transfer.infrastructure.persistence.IdempotencyRepository.IdempotencyRecord;
import com.moneytransfer.transfer.infrastructure.persistence.TransferJpaRepository;
import com.moneytransfer.transfer.web.dto.TransferRequest;
import com.moneytransfer.transfer.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * POST /transfers, made safe to repeat (Challenge 2):
 * <ol>
 *   <li>Check the business rules (BR1, BR4).</li>
 *   <li>Claim the Idempotency-Key with the request fingerprint ({@link TransferClaimer}).</li>
 *   <li>Winner: run the saga, store the final response with the key.</li>
 *   <li>Loser (key already claimed):
 *     <ul>
 *       <li>different fingerprint: 422 IDEMPOTENCY_KEY_REUSED</li>
 *       <li>response stored: replay it exactly</li>
 *       <li>still running: 409 REQUEST_IN_PROGRESS (with the transferId to poll)</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Service
public class TransferApplicationService {

    public record Result(StoredResponse response, boolean replayed) { }

    private static final Logger log = LoggerFactory.getLogger(TransferApplicationService.class);

    private final TransferRules rules;
    private final TransferClaimer claimer;
    private final IdempotencyRepository idempotency;
    private final TransferJpaRepository transfers;
    private final TransferSagaOrchestrator orchestrator;
    private final ResponseRenderer renderer;

    public TransferApplicationService(TransferRules rules, TransferClaimer claimer, IdempotencyRepository idempotency,
                                      TransferJpaRepository transfers, TransferSagaOrchestrator orchestrator,
                                      ResponseRenderer renderer) {
        this.rules = rules;
        this.claimer = claimer;
        this.idempotency = idempotency;
        this.transfers = transfers;
        this.orchestrator = orchestrator;
        this.renderer = renderer;
    }

    public Result submit(String idempotencyKey, TransferRequest request) {
        List<TransferRules.Violation> violations = rules.check(
                request.sourceAccountId(), request.destinationAccountId(), request.amount());
        if (!violations.isEmpty()) {
            throw new ApiException(422, violations.getFirst().code(), violations.getFirst().message(),
                    violations.stream()
                            .map(v -> Map.<String, Object>of("field", v.field(), "rule", v.code(), "issue", v.message()))
                            .toList());
        }

        String currency = request.currency().toUpperCase(Locale.ROOT);
        String hash = RequestFingerprint.of(request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), currency);
        Transfer fresh = Transfer.create(UUID.randomUUID(), idempotencyKey, request.sourceAccountId(),
                request.destinationAccountId(), request.amount(), currency);

        Transfer claimed;
        try {
            claimed = claimer.claim(idempotencyKey, hash, fresh);
        } catch (DataAccessException e) {
            // Usually a duplicate key. If the key really exists, this request is a repeat.
            Optional<IdempotencyRecord> existing = findAfterConflict(idempotencyKey);
            if (existing.isEmpty()) {
                throw e;
            }
            return replay(existing.get(), hash);
        }

        log.info("Idempotency key {} claimed for new transfer {}", idempotencyKey, claimed.getId());
        Transfer result = orchestrator.execute(claimed);
        StoredResponse response = renderer.render(result);
        if (result.getStatus().isTerminal()) {
            idempotency.complete(idempotencyKey, response.status(), response.json());
        }
        return new Result(response, false);
    }

    /** Called by the recovery job once a stuck transfer reaches a final state. */
    public void completeIdempotencyRecord(Transfer t) {
        if (t.getStatus().isTerminal()) {
            StoredResponse response = renderer.render(t);
            idempotency.complete(t.getIdempotencyKey(), response.status(), response.json());
        }
    }

    /**
     * PostgreSQL makes the losing INSERT wait until the winner commits, so the winner's row is
     * visible at once. Some databases report the conflict before the winner commits; looking
     * again for up to a second covers that case too.
     */
    private Optional<IdempotencyRecord> findAfterConflict(String key) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Optional<IdempotencyRecord> record = idempotency.find(key);
            if (record.isPresent()) {
                return record;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    private Result replay(IdempotencyRecord record, String hash) {
        if (!record.requestHash().equals(hash)) {
            log.warn("Idempotency key {} reused with a different request body", record.key());
            throw new ApiException(422, "IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used with a different request body");
        }
        if (record.status() == IdempotencyRepository.Status.COMPLETED) {
            log.info("Idempotency key {} seen before; replaying stored {} response for transfer {}",
                    record.key(), record.responseCode(), record.transferId());
            return new Result(new StoredResponse(record.responseCode(), record.responseBody(), record.transferId()), true);
        }

        Transfer t = transfers.findById(record.transferId())
                .orElseThrow(() -> new IllegalStateException("Idempotency record points at missing transfer"));
        if (t.getStatus().isTerminal()) {
            // Finished, but the first request has not stored its response yet (or crashed before it).
            completeIdempotencyRecord(t);
            log.info("Idempotency key {} seen before; transfer {} is {}", record.key(), t.getId(), t.getStatus());
            return new Result(renderer.render(t), true);
        }
        log.info("Idempotency key {} seen before; transfer {} still {}", record.key(), t.getId(), t.getStatus());
        throw new ApiException(409, "REQUEST_IN_PROGRESS",
                "A request with this Idempotency-Key is still being processed; poll GET /api/v1/transfers/" + t.getId(),
                List.of(Map.of("transferId", t.getId().toString(), "status", t.getStatus().name())));
    }
}
