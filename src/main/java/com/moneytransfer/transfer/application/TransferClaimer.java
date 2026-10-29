package com.moneytransfer.transfer.application;

import com.moneytransfer.transfer.domain.Transfer;
import com.moneytransfer.transfer.infrastructure.persistence.IdempotencyRepository;
import com.moneytransfer.transfer.infrastructure.persistence.TransferJpaRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Challenge 2, step 1: claim the Idempotency-Key and create the PENDING transfer in one short
 * transaction.
 *
 * The INSERT into idempotency_keys is what makes concurrent duplicates safe. When two identical
 * requests arrive at the same moment, the database lets one INSERT commit and makes the other
 * wait, then fail with a duplicate-key error. A check-then-insert in Java ("SELECT; if missing,
 * INSERT") would let both through, because both SELECTs can run before either INSERT.
 */
@Component
public class TransferClaimer {

    private final IdempotencyRepository idempotency;
    private final TransferJpaRepository transfers;

    public TransferClaimer(IdempotencyRepository idempotency, TransferJpaRepository transfers) {
        this.idempotency = idempotency;
        this.transfers = transfers;
    }

    /** @throws DuplicateKeyException if the key was already claimed (now or earlier) */
    @Transactional
    public Transfer claim(String idempotencyKey, String requestHash, Transfer newTransfer) {
        idempotency.insert(idempotencyKey, requestHash, newTransfer.getId());
        return transfers.saveAndFlush(newTransfer);
    }
}
