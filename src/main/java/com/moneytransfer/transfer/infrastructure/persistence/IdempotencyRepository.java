package com.moneytransfer.transfer.infrastructure.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain SQL on purpose: the claim must be a real INSERT so the primary key decides the winner
 * when two identical requests arrive at the same moment.
 */
@Repository
public class IdempotencyRepository {

    public enum Status { IN_PROGRESS, COMPLETED }

    public record IdempotencyRecord(String key, String requestHash, UUID transferId, Status status,
                                    Integer responseCode, String responseBody) { }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @throws DuplicateKeyException if the key already exists, including when another transaction
     *         inserted it a moment ago (the database makes the second insert wait, then fail).
     */
    public void insert(String key, String requestHash, UUID transferId) {
        jdbc.update("""
                INSERT INTO idempotency_keys (idempotency_key, request_hash, transfer_id, status, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, key, requestHash, transferId, Status.IN_PROGRESS.name(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public Optional<IdempotencyRecord> find(String key) {
        return jdbc.query("""
                SELECT idempotency_key, request_hash, transfer_id, status, response_code, response_body
                FROM idempotency_keys WHERE idempotency_key = ?
                """,
                (rs, i) -> new IdempotencyRecord(
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        rs.getObject("transfer_id", UUID.class),
                        Status.valueOf(rs.getString("status")),
                        (Integer) rs.getObject("response_code"),
                        rs.getString("response_body")),
                key).stream().findFirst();
    }

    /** Stores the final response. Only the first completion counts. */
    public boolean complete(String key, int responseCode, String responseBody) {
        return jdbc.update("""
                UPDATE idempotency_keys
                SET status = ?, response_code = ?, response_body = ?, completed_at = ?
                WHERE idempotency_key = ? AND status = ?
                """, Status.COMPLETED.name(), responseCode, responseBody, OffsetDateTime.now(ZoneOffset.UTC),
                key, Status.IN_PROGRESS.name()) == 1;
    }
}
